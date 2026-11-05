"""Recovery and fault checks for a fresh, synthetic integration project."""

import hashlib
import json
import os
import re
import secrets
import subprocess
import time
from urllib.parse import urlencode
from uuid import UUID

MYSQL_IMAGE = "mysql:8.4.10@sha256:c592c15aaf4a1961e15d82eb31ea5987dda862d1c4b1e93424438c0e91dc1f8d"
RABBIT_QUEUE = "finguard.copilot.analysis.request.v1"
RABBIT_EXCHANGE = "finguard.copilot.analysis.v1"
FAULT_CASES = ("core", "mysql", "redis", "qdrant", "model-fixture", "telemetry", "agent")


def canonical_mysql_dump(dump):
    # MySQL may spell out the charset already implied by this collation after import.
    return re.sub(r"(?m)^(  `[a-z_]+` longtext) CHARACTER SET utf8mb4 (?=COLLATE utf8mb4_0900_bin\b)",
                  r"\1 ", dump)


def eventually(probe, description, timeout=90):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        value = probe()
        if value:
            return value
        time.sleep(1)
    raise RuntimeError(f"Timed out waiting for {description}")


class Recovery:
    def __init__(self, compose, settings, report, check, sql, request, reviewer, task_id):
        self.compose = compose
        self.settings = settings
        self.report = report
        self.check = check
        self.sql = sql
        self.request = request
        self.reviewer = reviewer
        self.task_id = task_id
        self.api = f"http://127.0.0.1:{settings['FG_WEB_PORT']}/api/copilot/review-analyses"

    def json(self, url, **kwargs):
        with self.request(url, token=self.reviewer, **kwargs) as response:
            return json.load(response)

    def submit(self, key):
        return str(UUID(self.json(self.api, data={"reviewTaskId": self.task_id},
                                  headers={"Idempotency-Key": key}, expected=202)["analysisId"]))

    def terminal(self, analysis_id):
        def probe():
            result = self.json(self.api + "/" + analysis_id)
            return result if result["status"] in ("SUCCEEDED", "FAILED") else None

        return eventually(probe, "analysis terminal state", timeout=120)

    def calls(self):
        return self.local_json("http://127.0.0.1:18765/_calls")["calls"]

    def local_json(self, url, *, rabbit=False, payload=None, auth=None, bearer=None, method=None, expected=200):
        data = {"url": url, "payload": payload, "method": method, "expected": expected}
        if rabbit:
            auth = "finguard:" + self.settings["FG_RABBIT_PASSWORD"]
        if auth:
            data["auth"] = auth
        if bearer:
            data["bearer"] = bearer
        return json.loads(self.compose(
            "exec", "-T", "model-fixture", "python", "-c",
            """import base64, json, sys, urllib.error, urllib.request
x=json.load(sys.stdin)
headers={}
if 'auth' in x:
    headers['Authorization']='Basic '+base64.b64encode(x['auth'].encode()).decode()
if 'bearer' in x:
    headers['Authorization']='Bearer '+x['bearer']
body=x['payload']
if body is not None:
    headers['Content-Type']='application/json'
req=urllib.request.Request(x['url'], data=json.dumps(body).encode() if body is not None else None,
                           headers=headers, method=x['method'])
try:
    res=urllib.request.urlopen(req, timeout=8)
except urllib.error.HTTPError as error:
    res=error
with res:
    if res.code!=x['expected']:
        raise RuntimeError(f"HTTP {res.code}, expected {x['expected']}")
    print(res.read().decode() or 'null')
""", input=json.dumps(data), capture=True))

    def healthy(self, service):
        self.compose("up", "-d", "--no-build", "--no-deps", "--wait", "--wait-timeout", "120", service)

    def restore_mysql(self, directory):
        target = self.report["project"] + "-restore-mysql"
        env = {**os.environ, "MYSQL_ROOT_PASSWORD": secrets.token_hex(24)}
        backup = directory / "synthetic-backup.sql"
        dump_command = ('MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysqldump --user=root --single-transaction '
                        '--set-gtid-purged=OFF --no-tablespaces --hex-blob --skip-comments --skip-extended-insert '
                        '--order-by-primary --databases core copilot')
        dump = self.compose(
            "exec", "-T", "mysql", "sh", "-ec", dump_command, capture=True,
        )
        self.check("backup includes Core and Copilot schema and data",
                   "USE `core`;" in dump and "USE `copilot`;" in dump
                   and dump.count("INSERT INTO ") > 10)
        backup.write_bytes(dump.encode("utf-8"))
        self.report["backup"] = {"sha256": hashlib.sha256(backup.read_bytes()).hexdigest(),
                                 "bytes": backup.stat().st_size}
        subprocess.run(["docker", "run", "--detach", "--name", target, "--network", "none",
                        "-e", "MYSQL_ROOT_PASSWORD", MYSQL_IMAGE], env=env, check=True, capture_output=True)
        try:
            def restored_sql(*args, query=None, check=True):
                return subprocess.run(
                    ["docker", "exec", "-i", target, "sh", "-ec",
                     'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql --host=127.0.0.1 --user=root '
                     '--batch --skip-column-names "$@"', "mysql", *args],
                    input=query, encoding="utf-8", capture_output=True, check=check,
                )

            eventually(lambda: restored_sql(query="SELECT 1;", check=False).returncode == 0,
                       "isolated MySQL restore server")
            restored_sql(query=backup.read_bytes().decode("utf-8"))
            actual = subprocess.check_output(["docker", "exec", target, "sh", "-ec", dump_command],
                                             encoding="utf-8")
            self.check("both restored schemas produce an identical canonical logical dump",
                       canonical_mysql_dump(actual) == canonical_mysql_dump(dump))
        finally:
            subprocess.run(["docker", "rm", "--force", "--volumes", target], check=True, capture_output=True)
            backup.unlink(missing_ok=True)

    def recover_outbox(self):
        before = self.calls()
        self.compose("stop", "rabbitmq")
        try:
            analysis_id = self.submit("phase6-outbox-recovery")
            where = f"analysis_id=UUID_TO_BIN('{analysis_id}')"
            pending = self.sql("copilot", f"SELECT COUNT(*) FROM outbox_event WHERE {where} AND published_at IS NULL;")
            self.check("RabbitMQ outage leaves accepted analysis in durable pending Outbox",
                       pending == "1" and self.calls() == before)
        finally:
            self.healthy("rabbitmq")
        self.check("pending Outbox reaches a successful result after RabbitMQ restart",
                   self.terminal(analysis_id)["status"] == "SUCCEEDED")
        event = self.sql("copilot", f"SELECT CAST(payload_json AS CHAR) FROM outbox_event WHERE {where} AND published_at IS NOT NULL;")
        url = "http://rabbitmq:15672/api"
        queue = f"{url}/queues/%2F/{RABBIT_QUEUE}"
        prior = self.local_json(queue, rabbit=True).get("message_stats", {}).get("ack", 0)
        result = self.local_json(f"{url}/exchanges/%2F/{RABBIT_EXCHANGE}/publish", rabbit=True, payload={
            "properties": {"content_type": "application/json",
                           "headers": {"__TypeId__": "com.finguard.copilot.dispatch.AnalysisRequestedMessage"}},
            "routing_key": "analysis.requested", "payload": event, "payload_encoding": "string",
        })
        self.check("replayed original event was routed by RabbitMQ", result["routed"])

        def acked():
            current = self.local_json(queue, rabbit=True)
            return (current.get("message_stats", {}).get("ack", 0) > prior
                    and current["messages_ready"] == current["messages_unacknowledged"] == 0)

        eventually(acked, "broker acknowledgement of replay")
        self.check("redelivery produces one receipt and one model invocation total",
                   self.sql("copilot", f"SELECT COUNT(*) FROM analysis_message_receipt WHERE {where};") == "1"
                   and self.sql("copilot", f"SELECT COUNT(*) FROM model_usage_ledger WHERE {where};") == "1"
                   and self.calls() == before + 1)

    def rebuild_vectors(self):
        project = self.report["project"]
        volume = project + "_qdrant-data"
        owner = subprocess.check_output(["docker", "volume", "inspect", "--format",
                                         '{{index .Labels "com.docker.compose.project"}}', volume],
                                        text=True).strip()
        if owner != project:
            raise ValueError("Qdrant volume is not owned by this drill")
        self.compose("stop", "qdrant")
        self.compose("rm", "--force", "qdrant")
        subprocess.run(["docker", "volume", "rm", volume], check=True)
        self.healthy("qdrant")
        empty = self.local_json("http://qdrant:6333/collections")["result"]["collections"]
        self.check("replacement Qdrant has an empty vector store", not empty)
        raw = self.compose("run", "--build", "--rm", "--no-deps", "-T", "-e",
                           "E2E_INDEX_VERSION=phase6-rebuilt-v1", "knowledge-seed",
                           "--rebuild-fixture", capture=True)
        rebuilt = json.loads(raw.strip().splitlines()[-1])
        self.report["rebuiltIndex"] = rebuilt
        self.check("new index rebuilt from SQL membership and passes fixture citation evaluation",
                   rebuilt["indexVersion"] == "phase6-rebuilt-v1" and rebuilt["status"] == "PUBLISHED"
                   and rebuilt["retrievedChunk"] == self.report["knowledge"]["retrievedChunk"]
                   and rebuilt["recallAtK"] == rebuilt["citationPrecision"] == 1)
        analysis_id = self.submit("phase6-after-vector-rebuild")
        analysis = self.terminal(analysis_id)
        manifest_version = self.sql("copilot", "SELECT JSON_UNQUOTE(JSON_EXTRACT(manifest_json,'$.index.version')) "
                                    f"FROM model_run_manifest WHERE analysis_id=UUID_TO_BIN('{analysis_id}');")
        self.check("review flow succeeds after vector loss using the rebuilt index",
                   analysis["status"] == "SUCCEEDED" and manifest_version == rebuilt["indexVersion"] and any(
                       citation["documentId"] == rebuilt["documentId"]
                       for citation in analysis["result"]["policyCitations"]))

    def run(self, directory):
        self.restore_mysql(directory)
        self.recover_outbox()
        self.rebuild_vectors()


class Faults(Recovery):
    def successful(self, key):
        analysis_id = self.submit("phase6-" + re.sub(r"[^a-z0-9-]", "-", key.lower()))
        self.check(key, self.terminal(analysis_id)["status"] == "SUCCEEDED")
        return analysis_id

    def http_outage(self, service, statuses):
        calls = self.calls()
        self.compose("stop", service)
        try:
            with self.request(self.api, token=self.reviewer, data={"reviewTaskId": self.task_id},
                              headers={"Idempotency-Key": "phase6-outage-" + service},
                              expected=statuses) as response:
                self.report.setdefault("faultCodes", {})[service] = response.code
                self.check(f"{service} outage rejects the write-free analysis request safely",
                           "analysisId" not in json.load(response) and self.calls() == calls)
        finally:
            self.healthy(service)
        self.successful("successful analysis after " + service + " restart")

    def worker_outage(self, service, codes, provider=False):
        before = None if provider else self.calls()
        self.compose("stop", service)
        try:
            analysis_id = self.submit("phase6-outage-" + service)
            result = self.terminal(analysis_id)
            self.report.setdefault("faultCodes", {})[service] = result["failureCode"]
            self.check(f"{service} outage terminates safely without publishing a recommendation",
                       result["status"] == "FAILED" and result["failureCode"] in codes
                       and result.get("result") is None and (provider or self.calls() == before))
            if provider:
                where = f"analysis_id=UUID_TO_BIN('{analysis_id}')"
                self.check("uncertain model response leaves a single durable call and budget hold",
                           self.sql("copilot", f"SELECT COUNT(*) FROM model_call_reservation WHERE {where};") == "1"
                           and self.sql("copilot", f"SELECT status FROM model_budget_reservation WHERE {where};")
                           == "UNKNOWN_HELD")
        finally:
            self.healthy(service)
        self.successful("successful analysis after " + service + " restart")

    def trace_observations(self, analysis_id, phase):
        trace = self.sql("copilot", f"SELECT trace_id FROM analysis_run WHERE id=UUID_TO_BIN('{analysis_id}');")
        if not re.fullmatch(r"[a-f0-9]{32}", trace):
            raise ValueError("Missing durable trace ID")
        auth = self.settings["COPILOT_LANGFUSE_PUBLIC_KEY"] + ":" + self.settings["COPILOT_LANGFUSE_SECRET_KEY"]
        url = "http://langfuse:3000/api/public/v2/observations?" + urlencode({"traceId": trace, "fields": "core"})
        rows = eventually(lambda: self.local_json(url, auth=auth).get("data"), "Langfuse trace ingestion", timeout=90)
        self.report.setdefault("langfuse", {})[analysis_id] = {"traceId": trace, "observations": len(rows)}
        self.check(f"Langfuse receives deployed analysis spans {phase}", len(rows) > 0)

    def telemetry_outage(self, langfuse):
        self.trace_observations(self.report["analysisId"], "before outage")
        def metrics(expression):
            url = "http://prometheus:9090/api/v1/query?" + urlencode({"query": expression})
            return [float(metric["value"][1]) for metric in self.local_json(url)["data"]["result"]]

        eventually(lambda: 1 in metrics('up{job="finguard-copilot"}'), "Prometheus scrape")
        self.check("Prometheus scrapes Copilot and Grafana is healthy",
                   self.local_json("http://grafana:3000/api/health")["database"] == "ok")
        failed_exports = 'copilot_telemetry_dropped_total{destination="langfuse",reason="EXPORT_FAILED"}'
        before = sum(metrics(failed_exports))
        langfuse("stop", "web")
        try:
            self.successful("successful analysis while Langfuse is offline")
            eventually(lambda: sum(metrics(failed_exports)) > before, "bounded telemetry failure metric")
            self.check("Langfuse outage records export failure while analysis succeeds", True)
        finally:
            langfuse("up", "--no-deps", "--wait", "--wait-timeout", "150", "web")
        self.trace_observations(self.successful("successful analysis after Langfuse restart"), "after restart")

    def agent(self, ops):
        self.compose("-f", str(ops / "agent.yml"), "up", "--no-build", "--wait", "--wait-timeout", "180")
        before = self.calls()
        analysis_id = self.submit("phase6-agent")
        path = f"{self.api}/{analysis_id}"

        def paused():
            view = self.json(path + "/view")
            return view if view["status"] in ("WAITING_FOR_HUMAN", "FAILED", "SUCCEEDED") else None

        page = eventually(paused, "Agent human gate")
        self.check("containerized native Agent durably pauses for human confirmation",
                   page["status"] == "WAITING_FOR_HUMAN" and self.calls() == before + 1)
        self.compose("-f", str(ops / "agent.yml"), "restart", "copilot")
        self.compose("-f", str(ops / "agent.yml"), "up", "--no-build", "--wait", "--wait-timeout", "180")
        restored = self.json(path + "/view")
        revision = page["agent"]["revision"]
        self.check("waiting revision survives a Copilot container restart",
                   restored["status"] == "WAITING_FOR_HUMAN" and restored["agent"]["revision"] == revision
                   and restored["agent"]["modelCalls"] == 1)
        calls_after_restart = self.calls()
        headers = {"Idempotency-Key": "phase6-agent-continue"}
        resume = {"expectedRevision": revision, "action": "CONTINUE"}
        self.json(path + "/resume", headers=headers, data=resume, expected=202)
        self.check("repeated human continuation replays the same receipt",
                   self.json(path + "/resume", headers=headers, data=resume, expected=202)["replayed"])
        self.check("Agent reaches validated success after container restart", self.terminal(analysis_id)["status"] == "SUCCEEDED")
        trace = self.json(path + "/trace")
        where = f"analysis_id=UUID_TO_BIN('{analysis_id}')"
        settled = int(self.sql("copilot", f"SELECT COUNT(*) FROM agent_model_call WHERE {where} AND status='SETTLED';"))
        self.report["agent"] = {"analysisId": analysis_id, "revision": revision, "settledCalls": settled,
                                "traceCalls": len(trace["calls"]), "traceTools": len(trace["tools"])}
        self.check("Agent deployment records exactly two native calls and two tool executions",
                   len(trace["calls"]) == len(trace["tools"]) == settled == 2
                   and self.calls() == calls_after_restart + 1)
        scope = {"toolScope": ["get_review_facts", "get_task_state", "search_review_policy"]}
        with self.request(path + "/mcp-capabilities", token=self.reviewer, data=scope, expected=403):
            self.check("deployed frontend proxy cannot issue local MCP capabilities", True)
        local = f"http://127.0.0.1:8080/api/copilot/review-analyses/{analysis_id}"
        grant = self.local_json(local + "/mcp-capabilities", expected=201, bearer=self.reviewer, payload=scope)
        result = self.compose(
            "-f", str(ops / "agent.yml"), "run", "--build", "--rm", "--no-deps", "-T", "mcp-client", "-c",
            "import os,runpy,sys; os.environ['FINGUARD_MCP_CAPABILITY']=sys.stdin.readline().strip(); "
            "sys.argv=['/app/acceptance_client.py','--analysis-id',sys.argv[1]]; "
            "runpy.run_path(sys.argv[0],run_name='__main__')",
            analysis_id, input=grant["capability"] + "\n", capture=True,
        )
        mcp = json.loads(result.strip().splitlines()[-1])
        self.check("real local STDIO MCP respects the same four-slot Agent budget",
                   mcp["initialized"] and mcp["successfulReads"] == 2 and mcp["budgetRejected"])
        self.local_json(local + "/mcp-capabilities", bearer=self.reviewer, method="DELETE", expected=204)
        self.local_json(local + "/mcp-tools", bearer=grant["capability"], expected=401)
        self.check("revoked STDIO capability is denied on the container loopback", True)
        self.report["agent"]["mcp"] = mcp

    def run_faults(self, langfuse, ops, cases):
        drills = (
            ("core", lambda: self.http_outage("core", 503)),
            ("mysql", lambda: self.http_outage("mysql", (500, 503))),
            ("redis", lambda: self.worker_outage("redis", ("MODEL_CAPACITY_UNAVAILABLE",))),
            ("qdrant", lambda: self.worker_outage("qdrant", ("MODEL_CONFIGURATION_INVALID",))),
            ("model-fixture", lambda: self.worker_outage("model-fixture", ("MODEL_TIMEOUT", "MODEL_UNAVAILABLE"),
                                                        provider=True)),
            ("telemetry", lambda: self.telemetry_outage(langfuse)),
            ("agent", lambda: self.agent(ops)),
        )
        failures = {}
        for name, drill in drills:
            if name not in cases:
                continue
            try:
                drill()
            except Exception as error:
                failures[name] = f"{type(error).__name__}: {error}"
                print(f"FAIL {name}: {failures[name]}", flush=True)
        self.report["faultErrors"] = failures
        self.check("all selected fault and Agent scenarios passed", not failures)
