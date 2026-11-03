"""Seven-day cleanup for the isolated local synthetic Langfuse project only."""

import argparse
import base64
from datetime import datetime, timedelta, timezone
import json
import os
import re
import time
from urllib.parse import urlencode
from urllib.request import Request, build_opener, HTTPRedirectHandler, ProxyHandler


def cleanup(project_id, now, send, *, apply=False):
    if not re.fullmatch(r"fg-p4-4-[a-f0-9]{32}", project_id or ""):
        raise ValueError("INVALID_PROJECT")
    if now.tzinfo is None:
        raise ValueError("INVALID_TIME")
    projects = send("GET", "/api/public/projects")
    project_rows = projects.get("data")
    if (not isinstance(project_rows, list) or len(project_rows) != 1
            or not isinstance(project_rows[0], dict) or project_rows[0].get("id") != project_id):
        raise ValueError("PROJECT_MISMATCH")
    cutoff = now - timedelta(days=7)
    candidates = set()
    partial = False
    cursor = None
    seen_cursors = set()
    for page in range(10):
        params = {"limit": 100, "fields": "core", "fromStartTime": "1970-01-01T00:00:00Z", "toStartTime": cutoff.isoformat()}
        if cursor is not None:
            params["cursor"] = cursor
        path = "/api/public/v2/observations?" + urlencode(params)
        response = send("GET", path)
        rows = response.get("data")
        metadata = response.get("meta")
        if not isinstance(rows, list) or len(rows) > 100 or not isinstance(metadata, dict):
            raise ValueError("INVALID_RESPONSE")
        for row in rows:
            trace_id, timestamp = row.get("traceId"), row.get("startTime")
            if not isinstance(trace_id, str) or not re.fullmatch("[a-f0-9]{32}", trace_id) or not isinstance(timestamp, str):
                raise ValueError("INVALID_TRACE")
            captured = datetime.fromisoformat(timestamp.replace("Z", "+00:00"))
            if captured.tzinfo is None:
                raise ValueError("INVALID_TIME")
            if captured < cutoff:
                candidates.add(trace_id)
        cursor = metadata.get("cursor")
        if cursor is None:
            break
        if not isinstance(cursor, str) or not cursor or len(cursor) > 8192 or cursor in seen_cursors:
            raise ValueError("INVALID_CURSOR")
        seen_cursors.add(cursor)
        if page == 9:
            partial = True
    inactive = set()
    for trace_id in sorted(candidates):
        recent = send("GET", "/api/public/v2/observations?" + urlencode({
            "traceId": trace_id, "limit": 1, "fields": "core", "fromStartTime": cutoff.isoformat(), "toStartTime": now.isoformat()}))
        if not isinstance(recent.get("data"), list):
            raise ValueError("INVALID_RESPONSE")
        if not recent["data"]:
            inactive.add(trace_id)
    candidates = inactive
    if apply and candidates:
        try:
            send("DELETE", "/api/public/traces", {"traceIds": sorted(candidates)})
        except Exception:
            return {"status": "DELETE_OUTCOME_UNKNOWN", "retentionDays": 7, "dryRun": False,
                    "candidateCount": len(candidates), "requestedDeletionCount": None,
                    "deletionVerification": "UNKNOWN"}
    return {"status": "PARTIAL" if partial else "OK", "retentionDays": 7, "dryRun": not apply,
            "candidateCount": len(candidates), "requestedDeletionCount": len(candidates) if apply else 0,
            "deletionVerification": "ASYNC_NOT_VERIFIED" if apply and candidates else "NOT_REQUESTED"}


class NoRedirect(HTTPRedirectHandler):
    def redirect_request(self, *_args, **_kwargs):
        return None


def local_opener():
    return build_opener(ProxyHandler({}), NoRedirect())


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--apply", action="store_true")
    parser.add_argument("--loop", action="store_true")
    args = parser.parse_args()
    base = os.environ.get("LF_RETENTION_BASE_URL", "http://127.0.0.1:13001")
    if base not in {"http://127.0.0.1:13001", "http://web:3000"}:
        raise ValueError("LOCAL_ENDPOINT_REQUIRED")
    public = os.environ["COPILOT_LANGFUSE_PUBLIC_KEY"]
    secret = os.environ["COPILOT_LANGFUSE_SECRET_KEY"]
    authorization = "Basic " + base64.b64encode((public + ":" + secret).encode()).decode()
    opener = local_opener()
    def send(method, path, body=None):
        request = Request(base + path, data=json.dumps(body).encode() if body is not None else None,
                          headers={"Authorization": authorization, "Content-Type": "application/json"}, method=method)
        with opener.open(request, timeout=10) as response:
            data = response.read(2_097_153)
        if len(data) > 2_097_152:
            raise ValueError("RESPONSE_TOO_LARGE")
        return json.loads(data) if data else {}
    while True:
        try:
            result = cleanup(os.environ["LF_PROJECT_ID"], datetime.now(timezone.utc), send, apply=args.apply)
            print(json.dumps(result), flush=True)
            delay = 86400
        except Exception:
            print(json.dumps({"status": "UNAVAILABLE", "requestedDeletionCount": 0}), flush=True)
            delay = 300
            if not args.loop:
                return 1
        if not args.loop:
            return 0
        time.sleep(delay)


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (ValueError, KeyError):
        print(json.dumps({"status": "INVALID_LOCAL_CONFIGURATION"}))
        raise SystemExit(2)
