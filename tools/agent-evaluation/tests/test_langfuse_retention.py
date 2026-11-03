import importlib.util
from datetime import datetime, timezone
from pathlib import Path

import pytest
from urllib.parse import parse_qs, urlparse


def test_local_client_does_not_inherit_a_proxy(monkeypatch):
    monkeypatch.setenv("HTTP_PROXY", "http://proxy.example:8080")
    opener = module().local_opener()
    assert all(not getattr(handler, "proxies", {}) for handler in opener.handlers)


def test_delete_timeout_is_unknown_rather_than_zero_requested():
    project = "fg-p4-4-" + "a" * 32
    def send(method, path, body=None):
        if path == "/api/public/projects": return {"data": [{"id": project}]}
        if method == "DELETE": raise TimeoutError("response lost after acceptance")
        if "traceId=" in path: return {"data": [], "meta": {}}
        return {"data": [{"traceId": "b" * 32, "startTime": "2026-01-01T00:00:00Z"}], "meta": {}}
    result = module().cleanup(project, datetime.now(timezone.utc), send, apply=True)
    assert result["status"] == "DELETE_OUTCOME_UNKNOWN"
    assert result["requestedDeletionCount"] is None

PATH = Path(__file__).resolve().parents[3] / "scripts/e2e/langfuse_retention.py"


def module():
    spec = importlib.util.spec_from_file_location("langfuse_retention", PATH)
    value = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(value)
    return value


@pytest.mark.parametrize("apply", [False, True])
def test_only_old_traces_in_the_owned_project_are_requested_for_deletion(apply):
    calls = []
    project = "fg-p4-4-" + "a" * 32
    def send(method, path, body=None):
        calls.append((method, path, body))
        if path == "/api/public/projects":
            return {"data": [{"id": project}]}
        if "traceId=" in path: return {"data": [], "meta": {}}
        if method == "GET":
            return {"data": [{"traceId": "b" * 32, "startTime": "2026-10-01T00:00:00Z"},
                             {"traceId": "c" * 32, "startTime": "2026-11-02T00:00:00Z"}],
                    "meta": {}}
        return {}
    report = module().cleanup(project, datetime(2026, 11, 2, tzinfo=timezone.utc), send, apply=apply)
    assert report["requestedDeletionCount"] == (1 if apply else 0)
    if apply:
        assert calls[-1] == ("DELETE", "/api/public/traces", {"traceIds": ["b" * 32]})
    else:
        assert all(call[0] == "GET" for call in calls)


def test_dry_run_and_wrong_project_cannot_delete():
    project = "fg-p4-4-" + "a" * 32
    calls = []
    def send(method, path, body=None):
        calls.append(method)
        return {"data": [{"id": "different"}]}
    with pytest.raises(ValueError, match="PROJECT_MISMATCH"):
        module().cleanup(project, datetime.now(timezone.utc), send, apply=True)
    assert calls == ["GET"]
    with pytest.raises(ValueError, match="INVALID_PROJECT"):
        module().cleanup("production", datetime.now(timezone.utc), send, apply=True)


def test_invalid_time_and_pagination_fail_before_any_deletion():
    project = "fg-p4-4-" + "a" * 32
    calls = []
    def send(method, path, body=None):
        calls.append(method)
        if path == "/api/public/projects": return {"data": [{"id": project}]}
        return {"data": [{"traceId": "b" * 32, "startTime": "2026-01-01"}], "meta": {}}
    with pytest.raises(ValueError):
        module().cleanup(project, datetime.now(timezone.utc), send, apply=True)
    assert "DELETE" not in calls


def test_cursor_pages_deduplicate_and_keep_traces_with_recent_activity():
    project = "fg-p4-4-" + "a" * 32
    deletes = []
    def send(method, path, body=None):
        if path == "/api/public/projects": return {"data": [{"id": project}]}
        if method == "DELETE": deletes.append(body); return {}
        params = parse_qs(urlparse(path).query)
        if "traceId" in params:
            return {"data": [{"traceId": "c" * 32}] if params["traceId"] == ["c" * 32] else []}
        row = {"traceId": "b" * 32, "startTime": "2026-01-01T00:00:00Z"}
        if "cursor" not in params: return {"data": [row], "meta": {"cursor": "page2"}}
        return {"data": [row, {"traceId": "c" * 32, "startTime": "2026-01-01T00:00:00Z"}], "meta": {}}
    result = module().cleanup(project, datetime.now(timezone.utc), send, apply=True)
    assert result["candidateCount"] == 1
    assert deletes == [{"traceIds": ["b" * 32]}]


def test_repeating_cursor_fails_before_deletion():
    project = "fg-p4-4-" + "a" * 32
    methods = []
    def send(method, path, body=None):
        methods.append(method)
        if path == "/api/public/projects": return {"data": [{"id": project}]}
        return {"data": [], "meta": {"cursor": "same"}}
    with pytest.raises(ValueError, match="INVALID_CURSOR"):
        module().cleanup(project, datetime.now(timezone.utc), send, apply=True)
    assert "DELETE" not in methods
