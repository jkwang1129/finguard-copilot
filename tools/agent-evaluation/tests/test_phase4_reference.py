import pytest

from finguard_agent_evaluation.phase4_reference import run_case


BASE = {"case_id": "P4H01", "category": "boundary", "rule_code": "SYNTH_LIMIT",
        "currency": "USD", "amount": "100.00", "operator": "GTE",
        "threshold": "100.00", "policy_document_id": "SYNTH_POLICY_V1",
        "citation_ids": ["SYNTH_POLICY_V1"], "expected": "CONFIRMED"}


def test_reference_uses_same_bounded_output_contract():
    assert run_case(BASE) == {"case_id": "P4H01", "result":
                              {"status": "completed", "recommendation": "CONFIRMED"}}
    assert run_case({**BASE, "amount": "99.99"})["result"]["recommendation"] == "IGNORED"
    assert run_case({**BASE, "citation_ids": []})["result"]["recommendation"] == "INSUFFICIENT_EVIDENCE"
    assert run_case({**BASE, "expected": "IGNORED"})["result"]["recommendation"] == "CONFIRMED"


def test_reference_rejects_extra_or_invalid_case_fields():
    with pytest.raises(ValueError):
        run_case({**BASE, "raw_prompt": "secret"})
    with pytest.raises(ValueError):
        run_case({**BASE, "case_id": "bad space"})
