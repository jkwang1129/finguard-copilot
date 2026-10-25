from pathlib import Path

from finguard_agent_evaluation.dataset import load_cases


def test_phase3_golden_dataset_covers_required_sanitized_categories():
    path = Path(__file__).parents[1] / "data" / "phase3-golden.jsonl"
    cases = load_cases(path)

    assert len(cases) == 6
    assert {case.case_id for case in cases} == {
        "high-amount-001",
        "high-frequency-001",
        "duplicate-reconciliation-001",
        "suspicious-reconciliation-001",
        "insufficient-evidence-001",
        "injection-001",
    }
    assert any(case.is_injection_case for case in cases)
    assert any(case.is_insufficient_evidence for case in cases)
    assert all(case.review_task_id > 0 for case in cases)
