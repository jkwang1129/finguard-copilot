import pytest

from finguard_agent_evaluation.phase4_report import build_report


def test_report_is_sorted_and_counts_all_statuses():
    cases = [{"case_id": "z", "expected": "CONFIRMED"},
             {"case_id": "a", "expected": "IGNORED"},
             {"case_id": "b", "expected": "INSUFFICIENT_EVIDENCE"}]
    results = {"z": {"status": "failed"}, "a": {"status": "completed", "recommendation": "IGNORED"}}
    report = build_report(cases, results, "a" * 64, "dataset-v1")
    assert [item["case_id"] for item in report["cases"]] == ["a", "b", "z"]
    assert report["counts"] == {"completed": 1, "failed": 1, "not_run": 1}
    assert report["refused"] == 0
    assert report["cases"][0]["matches_label"] is True
    assert report["version_verification"] == "unverified"
    assert report == build_report(list(reversed(cases)), results, "a" * 64, "dataset-v1")


def test_rejects_extra_duplicate_and_invalid_results():
    cases = [{"case_id": "a", "expected": "IGNORED"}]
    with pytest.raises(ValueError):
        build_report(cases * 2, {}, "a" * 64, "v1")
    with pytest.raises(ValueError):
        build_report(cases, {"other": {"status": "failed"}}, "a" * 64, "v1")
    with pytest.raises(ValueError):
        build_report(cases, {"a": {"status": "completed", "recommendation": "BAD"}}, "a" * 64, "v1")
    with pytest.raises(ValueError):
        build_report(cases, {"a": {"status": "failed", "raw_response": "secret"}}, "a" * 64, "v1")


def test_refusal_is_a_completed_controlled_outcome():
    cases = [{"case_id": "a", "expected": "INSUFFICIENT_EVIDENCE"}]
    result = {"a": {"status": "completed", "recommendation": "INSUFFICIENT_EVIDENCE"}}
    report = build_report(cases, result, "a" * 64, "v1")
    assert report["cases"][0]["matches_label"] is True
    assert report["refused"] == 1
