#!/usr/bin/env python3
"""Build and verify one isolated Core/Copilot Compose project."""

import argparse
import base64
from decimal import Decimal
import json
import os
from pathlib import Path
import re
import secrets
import shlex
import shutil
import subprocess
import tarfile
import time
from urllib.error import HTTPError
from urllib.request import ProxyHandler, Request, build_opener

ROOT = Path(__file__).resolve().parents[1]
INTEGRATION = ROOT / "ops/integration"
HTTP = build_opener(ProxyHandler({}))
CORE_TABLES = (
    "accounts", "transactions", "import_jobs", "reconciliation_jobs",
    "reconciliation_results", "risk_hits", "review_tasks", "audit_logs",
)


def output(*command):
    return subprocess.check_output(command, cwd=ROOT, text=True).strip()


def write_environment(path, core_source, core_port, web_port, telemetry_project=None):
    settings = {
        "FG_CORE_SOURCE": core_source.as_posix(),
        "FG_CORE_PORT": str(core_port),
        "FG_WEB_PORT": str(web_port),
        "FG_JWT_SECRET": base64.b64encode(secrets.token_bytes(32)).decode(),
    }
    for name in ("MYSQL_ROOT", "CORE_DB", "COPILOT_DB", "RABBIT", "REDIS"):
        settings[f"FG_{name}_PASSWORD"] = secrets.token_hex(24)
    for name in ("ADMIN", "REVIEWER"):
        settings[f"FG_{name}_PASSWORD"] = secrets.token_hex(24) + "Aa1!"
    if telemetry_project:
        settings["FG_INTEGRATION_NETWORK"] = telemetry_project + "_backend"
        for name in ("POSTGRES_PASSWORD", "SALT", "ENCRYPTION_KEY", "CLICKHOUSE_PASSWORD", "REDIS_PASSWORD",
                     "MINIO_PASSWORD", "AUTH_SECRET", "ORG_ID", "PROJECT_ID", "USER_PASSWORD"):
            settings[f"LF_{name}"] = secrets.token_hex(32)
        settings["COPILOT_LANGFUSE_PUBLIC_KEY"] = "pk-lf-" + secrets.token_hex(16)
        settings["COPILOT_LANGFUSE_SECRET_KEY"] = "sk-lf-" + secrets.token_hex(24)
        settings["COPILOT_GRAFANA_ADMIN_PASSWORD"] = secrets.token_hex(24)
    with os.fdopen(os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600), "w", encoding="utf-8") as env:
        for name, value in settings.items():
            escaped = value.replace("'", "\\'")
            env.write(f"{name}='{escaped}'\n")
    return settings


def request(url, *, token=None, data=None, headers=None, expected=200, method=None):
    request_headers = dict(headers or {})
    if token:
        request_headers["Authorization"] = f"Bearer {token}"
    if data is not None:
        request_headers["Content-Type"] = "application/json"
        data = json.dumps(data).encode()
    try:
        response = HTTP.open(Request(url, data=data, headers=request_headers, method=method), timeout=12)
    except HTTPError as error:
        response = error
    accepted_codes = (expected,) if isinstance(expected, int) else expected
    if response.code not in accepted_codes:
        response.close()
        raise RuntimeError(f"{url} returned HTTP {response.code}, expected {expected}")
    return response


def read_json(url, **kwargs):
    with request(url, **kwargs) as response:
        return json.load(response, parse_float=Decimal)


def verify(compose, settings, report, recovery_directory=None, faults=None):
    def check(name, condition):
        report["checks"][name] = bool(condition)
        if not condition:
            raise RuntimeError(f"Acceptance failed: {name}")
        print(f"PASS {name}", flush=True)

    def sql(database, statement):
        return compose(
            "exec", "-T", "mysql", "sh", "-ec",
            'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql --user=root --batch --skip-column-names "$1"',
            "mysql", database, input=statement, capture=True,
        ).strip()

    def login(web, role):
        return read_json(
            web + "/core-api/api/auth/login",
            data={"username": f"e2e-{role}", "password": settings[f"FG_{role.upper()}_PASSWORD"]},
        )["accessToken"]

    services = ("mysql", "rabbitmq", "redis", "qdrant", "core", "copilot", "model-fixture", "frontend")
    published = {"core": settings["FG_CORE_PORT"], "frontend": settings["FG_WEB_PORT"]}
    for service in services:
        container = compose("ps", "-q", service, capture=True).strip()
        health, bindings = output(
            "docker", "inspect", "--format", "{{.State.Health.Status}} {{json .HostConfig.PortBindings}}", container
        ).split(" ", 1)
        expected = {"8080/tcp": [{"HostIp": "127.0.0.1", "HostPort": published[service]}]} if service in published else {}
        check(f"{service} healthy with expected host port bindings",
              health == "healthy" and (json.loads(bindings) or {}) == expected)
    backend = report["project"] + "_backend"
    check("backend network has no external route",
          output("docker", "network", "inspect", "--format", "{{.Internal}}", backend) == "true")
    for service in ("core", "copilot", "frontend", "model-fixture"):
        check(f"{service} runs as non-root", int(compose("exec", "-T", service, "id", "-u", capture=True)) > 0)
    for probe in ("liveness", "readiness"):
        compose("exec", "-T", "copilot", "wget", "-q", "-O", "/dev/null",
                f"http://127.0.0.1:9091/actuator/health/{probe}")
        check(f"Copilot {probe} probe responds", True)

    denied = compose(
        "exec", "-T", "mysql", "sh", "-ec",
        'MYSQL_PWD="$FG_COPILOT_DB_PASSWORD" exec mysql -h 127.0.0.1 -u copilot',
        input="SELECT id FROM core.review_tasks LIMIT 1;", capture=True, allow_failure=True,
    )
    check("Copilot SQL user cannot read Core tables",
          denied.returncode != 0 and bool(re.search(r"ERROR (1044|1142)\b", denied.stderr)))

    seed_log = compose("run", "--build", "--rm", "--no-deps", "-T", "knowledge-seed", capture=True)
    knowledge = json.loads(seed_log.strip().splitlines()[-1])
    check("fixture index published and retrieved through Qdrant", knowledge["status"] == "PUBLISHED")
    report["knowledge"] = knowledge
    task_id = int(sql("core", (INTEGRATION / "seed-review.sql").read_text(encoding="utf-8")))
    checksums = "CHECKSUM TABLE " + ",".join(CORE_TABLES) + ";"
    before = sql("core", checksums)
    check("Core business and audit checksums captured", len(before.splitlines()) == len(CORE_TABLES)
          and all(line.split()[-1].isdecimal() for line in before.splitlines()))

    web = "http://127.0.0.1:" + settings["FG_WEB_PORT"]
    core = "http://127.0.0.1:" + settings["FG_CORE_PORT"]
    with request(web + "/") as response:
        page = response.read().decode()
    assets = re.findall(r'(?:src|href)="(/assets/[^"]+)"', page)
    check("frontend HTML references built JavaScript", any(path.endswith(".js") for path in assets))
    for path in assets:
        with request(web + path) as response:
            check(f"frontend serves {path}", bool(response.read(1)))
    with request(core + "/demo/index.html") as response:
        check("Core review console serves HTML", response.headers.get_content_type() == "text/html")
    with request(web + "/core-api/api/review-tasks", expected=404):
        check("frontend proxy excludes Core business endpoints", True)

    reviewer = login(web, "reviewer")
    admin = login(web, "admin")
    identity = read_json(web + "/core-api/api/auth/me", token=reviewer)
    check("reviewer authenticates through frontend proxy", "REVIEWER" in identity["roles"])
    context = read_json(core + f"/api/review-tasks/{task_id}/context", token=reviewer)
    check("real Core returns the synthetic amount", context["transaction"]["amount"] == Decimal("120000.10"))
    api = web + "/api/copilot/review-analyses"
    body = {"reviewTaskId": task_id}
    headers = {"Idempotency-Key": "phase6-smoke"}
    for token, status in ((None, 401), (admin, 403)):
        with request(api, token=token, data=body, headers=headers, expected=status):
            check(f"analysis authorization returns {status}", True)
    accepted = read_json(api, token=reviewer, data=body, headers=headers, expected=202)
    analysis_id = accepted["analysisId"]
    report["analysisId"] = analysis_id
    replay = read_json(api, token=reviewer, data=body, headers=headers, expected=202)
    check("idempotent replay returns the same analysis ID", replay["analysisId"] == analysis_id)
    analysis = api + "/" + analysis_id
    deadline = time.monotonic() + 90
    while True:
        result = read_json(analysis, token=reviewer)
        if result["status"] in ("SUCCEEDED", "FAILED") or time.monotonic() >= deadline:
            break
        time.sleep(1)
    report["analysisStatus"] = result["status"]
    report["failureCode"] = result.get("failureCode")
    check("analysis reaches SUCCEEDED via Worker", result["status"] == "SUCCEEDED")
    check("validated result cites published policy", any(
        item["documentId"] == knowledge["documentId"] for item in result["result"]["policyCitations"]
    ))
    view = read_json(analysis + "/view", token=reviewer)
    check("review page API serves the validated result", view["analysisId"] == analysis_id
          and view["status"] == "SUCCEEDED" and view["result"]["analysisId"] == analysis_id)
    with request(analysis + "/events", token=reviewer, headers={"Accept": "text/event-stream"}) as stream:
        lines = [stream.readline(8192).strip() for _ in range(4)]
        check("frontend streams persisted SSE state", stream.headers.get_content_type() == "text/event-stream"
              and b"event:analysis-state" in lines and b"id:1" in lines)
    check("one published Outbox event and Worker receipt", sql("copilot", """
        SELECT COUNT(*) FROM outbox_event o JOIN analysis_message_receipt r ON o.id=r.event_id
        WHERE o.published_at IS NOT NULL;
    """) == "1")
    calls = compose("exec", "-T", "model-fixture", "wget", "-qO-", "http://127.0.0.1:18765/_calls", capture=True)
    check("one local model HTTP call and one zero-cost ledger entry", json.loads(calls)["calls"] == 1
          and sql("copilot", "SELECT COUNT(*), SUM(estimated_cost=0) FROM model_usage_ledger;") == "1\t1")
    check("Core review decision remains PENDING", sql("core", "SELECT status FROM review_tasks;") == "PENDING")
    check("Core business and audit checksums unchanged", sql("core", checksums) == before)
    if recovery_directory is not None:
        from phase6_drills import Recovery

        Recovery(compose, settings, report, check, sql, request, reviewer, task_id).run(recovery_directory)
        check("Core business and audit checksums unchanged after recovery drills", sql("core", checksums) == before)
    if faults is not None:
        from phase6_drills import Faults

        langfuse, cases = faults
        try:
            Faults(compose, settings, report, check, sql, request, reviewer, task_id).run_faults(
                langfuse, INTEGRATION, cases)
        finally:
            check("Core business and audit checksums unchanged after fault drills", sql("core", checksums) == before)


def main():
    from phase6_drills import FAULT_CASES

    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--core-root", required=True, type=Path)
    parser.add_argument("--core-ref", default="HEAD")
    parser.add_argument("--core-port", default=18080, type=int)
    parser.add_argument("--web-port", default=15173, type=int)
    parser.add_argument("--keep", action="store_true", help="keep a successful stack running for manual use")
    parser.add_argument("--recovery", action="store_true", help="exercise MySQL, RabbitMQ and Qdrant recovery")
    parser.add_argument("--faults", action="store_true", help="run fault drills with Langfuse and Agent")
    parser.add_argument("--fault-case", action="append", choices=FAULT_CASES, help="rerun selected fault cases")
    args = parser.parse_args()
    if not all(1024 <= port <= 65535 for port in (args.core_port, args.web_port)) or args.core_port == args.web_port:
        parser.error("provide distinct, unprivileged TCP ports")
    if args.fault_case and not args.faults:
        parser.error("--fault-case requires --faults")
    cases = (args.fault_case or FAULT_CASES) if args.faults else ()
    if cases and args.keep:
        parser.error("fault drills require automatic cleanup")
    telemetry = "telemetry" in cases

    project = "finguard-p6-" + secrets.token_hex(6)
    directory = ROOT / "target" / "integration" / project
    directory.mkdir(parents=True)
    env_file = directory / ".env"
    core_source = directory / "core-source"
    report_file = directory / "report.json"
    core_root = args.core_root.resolve(strict=True)
    core_revision = output("git", "-C", str(core_root), "rev-parse", "--verify", "--end-of-options", args.core_ref + "^{commit}")
    report = {"project": project, "coreRevision": core_revision, "copilotBase": output("git", "rev-parse", "HEAD"),
              "scope": "isolated Compose, P4 flow, local HTTP model fixture", "checks": {}}
    if cases:
        report["faultCases"] = cases
    command = ["docker", "compose", "-f", str(INTEGRATION / "compose.yml"),
               "--project-name", project, "--env-file", str(env_file)]
    langfuse_command = ["docker", "compose", "-f", str(ROOT / "ops/langfuse/compose.yml"),
                        "-f", str(INTEGRATION / "langfuse-network.yml"), "--project-name", project + "-lf",
                        "--env-file", str(env_file)]
    if telemetry:
        command.extend(("-f", str(INTEGRATION / "telemetry.yml")))
    environment = {key: value for key, value in os.environ.items()
                   if not key.startswith(("FG_", "COMPOSE_", "LF_", "COPILOT_LANGFUSE_", "COPILOT_GRAFANA_"))}

    def execute(base, *options, input=None, capture=False, allow_failure=False):
        result = subprocess.run([*base, *options], cwd=ROOT, env=environment, input=input,
                                encoding="utf-8", errors="replace", stdout=subprocess.PIPE if capture else None,
                                stderr=subprocess.PIPE if allow_failure else None, check=not allow_failure)
        return result if allow_failure else result.stdout

    def compose(*options, **kwargs):
        return execute(command, *options, **kwargs)

    def langfuse(*options, **kwargs):
        return execute(langfuse_command, *options, **kwargs)

    started = False
    telemetry_started = False
    passed = False
    try:
        archive = directory / "core.tar"
        subprocess.run(["git", "-C", str(core_root), "archive", "-o", str(archive), core_revision], check=True)
        with tarfile.open(archive) as source:
            source.extractall(core_source, filter="data")
        archive.unlink()
        settings = write_environment(env_file, core_source, args.core_port, args.web_port,
                                     project if telemetry else None)
        compose("config", "--quiet")
        started = True
        print(f"Starting {project}", flush=True)
        compose("up", "--build", "--wait", "--wait-timeout", "180")
        if telemetry:
            telemetry_started = True
            langfuse("up", "--wait", "--wait-timeout", "240")
        verify(compose, settings, report, directory if args.recovery else None,
               (langfuse, cases) if cases else None)
        passed = True
    finally:
        try:
            if started and not passed:
                logs = compose("logs", "--no-color", capture=True, allow_failure=True)
                (directory / "failure.log").write_text(logs.stdout, encoding="utf-8")
            if started and passed and args.keep:
                print(f"Reviewer password is in {env_file} (FG_REVIEWER_PASSWORD).")
                print(f"Frontend is at http://127.0.0.1:{args.web_port}/.")
                print("Cleanup: " + shlex.join([*command, "--profile", "seed", "down", "--volumes", "--rmi", "local"]))
            else:
                if started:
                    if telemetry_started:
                        if not passed:
                            logs = langfuse("logs", "--no-color", capture=True, allow_failure=True)
                            (directory / "langfuse-failure.log").write_text(logs.stdout, encoding="utf-8")
                        langfuse("down", "--volumes", "--remove-orphans")
                    agent_cleanup = ("-f", str(INTEGRATION / "agent.yml"), "--profile", "mcp") if "agent" in cases else ()
                    compose(*agent_cleanup, "--profile", "seed", "down", "--volumes", "--rmi", "local", "--remove-orphans")
                    projects = (project, project + "-lf") if telemetry_started else (project,)
                    for name in projects:
                        label = f"label=com.docker.compose.project={name}"
                        for resource, options in (("container", ["-a"]), ("network", []), ("volume", []), ("image", [])):
                            if output("docker", resource, "ls", *options, "-q", "--filter", label):
                                raise RuntimeError(f"Cleanup left project-owned {resource} resources")
                    report["cleanupVerified"] = True
                env_file.unlink(missing_ok=True)
                if core_source.exists():
                    source_path = core_source.resolve(strict=True)
                    if source_path != directory.resolve(strict=True) / "core-source":
                        raise RuntimeError("Unexpected Core snapshot path")
                    shutil.rmtree(source_path)
        finally:
            report["passed"] = passed and (args.keep or report.get("cleanupVerified", False))
            report_file.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
            print(f"Acceptance report: {report_file}", flush=True)


if __name__ == "__main__":
    main()
