import hashlib
import json

import pytest

from finguard_agent_evaluation.phase4_provider_judge import run_judge


CASE = {"case_id": "S1", "category": "boundary", "rule_code": "SYNTH_LIMIT",
        "currency": "USD", "amount": "100.00", "operator": "GTE",
        "threshold": "100.00", "policy_document_id": "SYNTH_POLICY_V1",
        "citation_ids": ["SYNTH_POLICY_V1"], "expected": "CONFIRMED"}


class FakeResponse:
    def __enter__(self):
        return self

    def __exit__(self, *args):
        pass

    def read(self):
        return json.dumps({"model": "deepseek-flash", "choices": [{"finish_reason": "stop",
            "message": {"content": '{"verdict":"CONFIRMED"}'}}],
            "usage": {"prompt_tokens": 250, "completion_tokens": 12}}).encode()


def test_real_judge_path_redacts_labels_and_writes_bounded_outcomes(tmp_path):
    dataset = tmp_path / "cases.jsonl"
    dataset.write_text(json.dumps(CASE) + "\n", encoding="utf-8")
    digest = hashlib.sha256(dataset.read_bytes()).hexdigest()
    output = tmp_path / "judge.jsonl"
    usage = tmp_path / "usage.jsonl"
    calls = []

    def opener(request, timeout):
        calls.append((request, timeout))
        return FakeResponse()

    result = run_judge(dataset, digest, output, usage, "test-secret", opener=opener)
    assert result == {"calls": 1, "estimated_cny": "0.000596"}
    assert len(calls) == 1
    assert b'"expected"' not in calls[0][0].data
    assert b'test-secret' not in output.read_bytes() + usage.read_bytes()
    rows = [json.loads(line) for line in output.read_text().splitlines()]
    assert rows[1] == {"case_id": "S1", "verdict": "CONFIRMED"}
    with pytest.raises(FileExistsError):
        run_judge(dataset, digest, output, usage, "test-secret", opener=opener)
    assert len(calls) == 1


def test_budget_and_hash_fail_before_calls(tmp_path):
    dataset = tmp_path / "cases.jsonl"
    dataset.write_text(json.dumps(CASE) + "\n", encoding="utf-8")
    calls = []
    for digest, budget in (("0" * 64, "10"),
                           (hashlib.sha256(dataset.read_bytes()).hexdigest(), "0.000001")):
        with pytest.raises(ValueError):
            run_judge(dataset, digest, tmp_path / "out.jsonl", tmp_path / "usage.jsonl",
                      "test-secret", max_spend_cny=budget, opener=lambda *a, **k: calls.append(1))
    assert calls == []


def test_oversized_case_fails_before_any_provider_call(tmp_path):
    dataset = tmp_path / "cases.jsonl"
    oversized = {**CASE, "citation_ids": ["SYNTH_POLICY_V1"] * 300}
    dataset.write_text(json.dumps(CASE) + "\n" + json.dumps(oversized | {"case_id": "S2"}) + "\n",
                       encoding="utf-8")
    calls = []
    with pytest.raises(ValueError):
        run_judge(dataset, hashlib.sha256(dataset.read_bytes()).hexdigest(),
                  tmp_path / "out.jsonl", tmp_path / "usage.jsonl", "test-secret",
                  opener=lambda *a, **k: calls.append(1))
    assert calls == []


def test_invalid_provider_output_stops_without_retry_or_raw_persistence(tmp_path):
    dataset = tmp_path / "cases.jsonl"
    dataset.write_text(json.dumps(CASE) + "\n", encoding="utf-8")
    calls = []

    class BadResponse(FakeResponse):
        def read(self):
            return json.dumps({"model": "deepseek-flash", "choices": [{"finish_reason": "stop",
                "message": {"content": '{"verdict":"BAD","secret":"raw"}'}}],
                "usage": {"prompt_tokens": 250, "completion_tokens": 12}}).encode()

    def opener(*args, **kwargs):
        calls.append(1)
        return BadResponse()

    output = tmp_path / "out.jsonl"
    usage = tmp_path / "usage.jsonl"
    with pytest.raises(ValueError):
        run_judge(dataset, hashlib.sha256(dataset.read_bytes()).hexdigest(),
                  output, usage, "test-secret", opener=opener)
    assert len(calls) == 1
    assert b"raw" not in output.read_bytes() + usage.read_bytes()
    rows = [json.loads(line) for line in usage.read_text().splitlines()]
    assert rows[1] == {"case_id": "S1", "status": "failed",
                       "estimated_cny": None, "reason_code": "PROVIDER_OR_PARSE_ERROR"}
