import json
from copy import deepcopy
from pathlib import Path

import pytest

from finguard_agent_evaluation import runner
from finguard_agent_evaluation.dataset import load_cases
from finguard_agent_evaluation.models import EvaluationCase
from finguard_agent_evaluation.report import build_report
from finguard_agent_evaluation import report as reporting


def case():
    return EvaluationCase(
        case_id="quality-contract-001", review_task_id=3001,
        input_facts={
            "transaction": {"id": 11, "accountId": 42, "amount": "12500.00", "currency": "USD"},
            "reviewTask": {"ruleCode": "RULE_HIGH_AMOUNT", "reasonCode": "REASON_AMOUNT_THRESHOLD"},
        },
        knowledge_evidence=[{
            "document_id": "policy-001", "version": "v1", "section": "thresholds",
            "content": "# Amount\nAmounts above USD 10000 require review.",
        }],
        reviewer_input="Review this case.", expected_recommendation="CONFIRMED",
        expected_fact_paths=["transaction.amount", "reviewTask.ruleCode"],
        expected_citation_ids=["policy-001"],
    )


def output():
    return {
        "schemaVersion": 1, "analysisId": "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
        "reviewTaskSummary": "Review USD 12500.", "riskRuleExplanation": "Amount exceeds threshold.",
        "businessEvidence": [{
            "transactionId": 11, "accountId": 42, "amount": 12500, "currency": "USD",
            "ruleCode": "RULE_HIGH_AMOUNT", "reasonCode": "REASON_AMOUNT_THRESHOLD",
            "sourcePaths": ["transaction.amount", "reviewTask.ruleCode"],
        }],
        "policyCitations": [{
            "documentId": "policy-001", "version": "v1", "sourcePath": "thresholds",
            "excerpt": "# Amount\nAmounts above USD 10000 require review.",
        }],
        "recommendation": "CONFIRMED", "reasoningSummary": "Review is supported, not proof of fraud.",
        "missingInformation": [], "limitations": ["Human review required."],
    }


def parse(value, expected=None):
    return runner.parse_provider_response(
        json.dumps({"choices": [{"message": {"content": json.dumps(value)}}],
                    "usage": {"prompt_tokens": 120, "completion_tokens": 30}}),
        latency_ms=100, estimated_cost=0, case=expected or case(),
        analysis_id="aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
    )


def test_verified_response_counts_exact_values_and_multiline_citation():
    actual = parse(output())
    assert actual.call_succeeded
    report = build_report([case()], [actual], dataset_version="quality-v2",
                          prompt_version="review-analysis-v2", model_configuration_version="qwen",
                          knowledge_index_version="quality-fixture-v2")
    assert report.fact_value_accuracy == 1
    assert report.citation_exact_match_rate == 1
    assert report.analysis_id_match_rate == 1
    assert report.fact_coverage == 1
    assert "Amounts above" not in report.model_dump_json()


@pytest.mark.parametrize("field,value", [
    ("transactionId", 12), ("accountId", 43), ("amount", 12501), ("currency", "EUR"),
    ("ruleCode", "RULE_OTHER"), ("reasonCode", "REASON_OTHER"),
])
def test_incorrect_fact_values_fail_even_with_correct_source_paths(field, value):
    result = output()
    result["businessEvidence"][0][field] = value
    actual = parse(result)
    assert not actual.call_succeeded
    assert actual.failure_category == "FACT_MISMATCH"
    assert actual.fact_paths == []
    assert actual.structured_json_valid
    assert actual.input_tokens == 120
    assert actual.output_tokens == 30


@pytest.mark.parametrize("field,value", [
    ("documentId", "policy-other"), ("version", "v2"), ("sourcePath", "other"),
    ("excerpt", "Amounts above USD 10000 require review."),
])
def test_citation_id_alone_cannot_earn_credit_for_wrong_provenance(field, value):
    result = output()
    result["policyCitations"][0][field] = value
    actual = parse(result)
    assert actual.failure_category == "CITATION_MISMATCH"
    assert not actual.call_succeeded
    assert actual.citation_ids == []


def test_wrong_analysis_id_is_rejected_and_usage_retained():
    result = output()
    result["analysisId"] = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"
    actual = parse(result)
    assert actual.failure_category == "ANALYSIS_ID_MISMATCH"
    assert actual.input_tokens == 120


def test_disallowed_source_path_fails_schema_validation():
    result = output()
    result["businessEvidence"][0]["sourcePaths"] = ["reconciliation.resultType"]
    assert parse(result).failure_category == "INVALID_SCHEMA"


def test_directional_result_without_policy_is_rejected():
    result = output()
    result["policyCitations"] = []
    assert parse(result).failure_category == "CITATION_REQUIRED"


def test_legitimate_insufficient_evidence_without_citation_is_accepted():
    result = output()
    result["recommendation"] = "INSUFFICIENT_EVIDENCE"
    result["policyCitations"] = []
    result["missingInformation"] = ["No usable policy supplied."]
    expected = case().model_copy(update={"knowledge_evidence": [], "expected_citation_ids": []})
    assert parse(result, expected).call_succeeded


def test_context_has_request_identity_and_copyable_citations_but_no_answer_labels():
    context = runner._case_context(case())
    assert "ANALYSIS_REQUEST" in context
    assert '"citation"' in context
    assert '"sourcePath": "thresholds"' in context
    assert "expected_recommendation" not in context
    assert "expected_fact_paths" not in context
    assert "CONFIRMED" not in context


def test_preflight_rejects_missing_ids_before_any_provider_call():
    incomplete = deepcopy(case().input_facts)
    del incomplete["transaction"]["id"]
    with pytest.raises(ValueError, match="transaction.id"):
        runner.validate_real_cases([case().model_copy(update={"input_facts": incomplete})])


def test_preflight_rejects_unrepresentable_expected_paths():
    invalid = case().model_copy(update={"expected_fact_paths": ["riskFacts[0].value"]})
    with pytest.raises(ValueError, match="source path"):
        runner.validate_real_cases([invalid])


def test_new_dataset_is_complete_and_keeps_all_six_business_categories():
    cases = load_cases(Path(__file__).parents[1] / "data" / "phase3-quality-v2.jsonl")
    runner.validate_real_cases(cases)
    assert len(cases) == 6
    assert {item.case_id for item in cases} == {
        "high-amount-001", "high-frequency-001", "duplicate-reconciliation-001",
        "suspicious-reconciliation-001", "insufficient-evidence-001", "injection-001",
    }
    assert [item.expected_recommendation for item in cases] == [
        "CONFIRMED", "CONFIRMED", "CONFIRMED", "INSUFFICIENT_EVIDENCE",
        "INSUFFICIENT_EVIDENCE", "INSUFFICIENT_EVIDENCE",
    ]


def test_new_prompt_is_loadable_with_the_unchanged_output_contract():
    assert '"analysisId"' in runner.evaluation_prompt("review-analysis-v2")


def test_real_runner_preflights_the_whole_batch_before_network(monkeypatch):
    def unexpected_network(*args, **kwargs):
        pytest.fail("invalid fixtures must not reach the provider")
    monkeypatch.setattr(runner, "urlopen", unexpected_network)
    monkeypatch.setenv("COPILOT_INPUT_PRICE_PER_MILLION", "0")
    monkeypatch.setenv("COPILOT_OUTPUT_PRICE_PER_MILLION", "0")
    invalid = case().model_copy(update={"case_id": "invalid-second", "expected_fact_paths": ["unsupported.path"]})
    with pytest.raises(ValueError, match="source path"):
        runner.run_real_results([case(), invalid], prompt_version="review-analysis-v2",
                                provider="qwen", model_id="qwen-plus", api_key="test-only")


def test_prompt_comparison_checks_input_fingerprints_and_reports_deltas():
    baseline = build_report([case()], [parse(output())], dataset_version="quality-v2",
                            prompt_version="review-analysis-v1", model_configuration_version="qwen",
                            knowledge_index_version="quality-fixture-v2")
    baseline = baseline.model_copy(update={"dataset_sha256": "a" * 64,
                                           "output_schema_sha256": "b" * 64})
    candidate = baseline.model_copy(update={"prompt_version": "review-analysis-v2"})
    compared = reporting.compare_prompt_reports(baseline, candidate)
    assert compared["deltas"]["fact_coverage"] == 0
    assert compared["total_calls"] == 2
    with pytest.raises(ValueError, match="same inputs"):
        reporting.compare_prompt_reports(
            baseline, candidate.model_copy(update={"dataset_sha256": "c" * 64}))


@pytest.mark.parametrize("bad_paths", [["transaction.amount", "transaction.amount"], ["unknown.path"]])
def test_preflight_rejects_invalid_expected_path_lists(bad_paths):
    with pytest.raises(ValueError, match="source path"):
        runner.validate_real_cases([case().model_copy(update={"expected_fact_paths": bad_paths})])


@pytest.mark.parametrize("field,value", [
    ("amount", "not-an-amount"), ("amount", "NaN"), ("amount", True),
    ("currency", 1), ("id", True),
])
def test_preflight_rejects_facts_that_cannot_be_copied_to_the_output_contract(field, value):
    facts = deepcopy(case().input_facts)
    facts["transaction"][field] = value
    with pytest.raises(ValueError, match="transaction"):
        runner.validate_real_cases([case().model_copy(update={"input_facts": facts})])


def test_preflight_rejects_duplicate_case_ids():
    with pytest.raises(ValueError, match="duplicate"):
        runner.validate_real_cases([case(), case()])


def test_comparison_cli_preserves_prompt_versions_instead_of_requiring_them_to_match(tmp_path):
    baseline = build_report([case()], [parse(output())], dataset_version="quality-v2",
                            prompt_version="review-analysis-v1", model_configuration_version="qwen",
                            knowledge_index_version="quality-fixture-v2").model_copy(update={
                                "dataset_sha256": "a" * 64, "output_schema_sha256": "b" * 64})
    candidate = baseline.model_copy(update={"prompt_version": "review-analysis-v2"})
    for name, report in (("baseline", baseline), ("candidate", candidate)):
        (tmp_path / f"{name}.json").write_text(report.model_dump_json(), encoding="utf-8")
    result = tmp_path / "comparison.json"
    assert runner.main([
        "--mode", "compare-prompts", "--reports", str(tmp_path / "baseline.json"),
        str(tmp_path / "candidate.json"), "--output", str(result),
    ]) == 0
    comparison = json.loads(result.read_text(encoding="utf-8"))
    assert comparison["baseline"]["prompt_version"] == "review-analysis-v1"
    assert comparison["candidate"]["prompt_version"] == "review-analysis-v2"


def test_deepseek_request_uses_json_mode_without_spending_output_budget_on_thinking(monkeypatch):
    from io import BytesIO
    requests = []
    def respond(request, **kwargs):
        requests.append(json.loads(request.data))
        value = output()
        value["analysisId"] = runner._analysis_id(case())
        return BytesIO(json.dumps({"choices": [{"message": {"content": json.dumps(value)}}],
                                  "usage": {"prompt_tokens": 10, "completion_tokens": 5}}).encode())
    monkeypatch.setattr(runner, "urlopen", respond)
    monkeypatch.setenv("COPILOT_INPUT_PRICE_PER_MILLION", "0")
    monkeypatch.setenv("COPILOT_OUTPUT_PRICE_PER_MILLION", "0")
    actual = runner.run_real_results([case()], prompt_version="review-analysis-v2",
                                     provider="deepseek", model_id="deepseek-flash", api_key="test-only")
    assert actual[0].call_succeeded
    assert len(requests) == 1
    assert requests[0]["response_format"] == {"type": "json_object"}
    assert requests[0]["thinking"] == {"type": "disabled"}


@pytest.mark.parametrize("value", [[], {}])
def test_invalid_recommendation_type_becomes_failed_sample_with_usage(value):
    result = output()
    result["recommendation"] = value
    actual = parse(result)
    assert actual.failure_category == "INVALID_SCHEMA"
    assert actual.input_tokens == 120


@pytest.mark.parametrize("expected_amount,model_literal", [
    ("12500.00", "12500.0000000000001"),
    ("90071992547409.94", "90071992547409.93"),
])
def test_decimal_amount_mismatch_cannot_be_hidden_by_float_rounding(expected_amount, model_literal):
    facts = deepcopy(case().input_facts)
    facts["transaction"]["amount"] = expected_amount
    expected = case().model_copy(update={"input_facts": facts})
    runner.validate_real_cases([expected])
    content = json.dumps(output()).replace('"amount": 12500', '"amount": ' + model_literal)
    actual = runner.parse_provider_response(
        json.dumps({"choices": [{"message": {"content": content}}],
                    "usage": {"prompt_tokens": 120, "completion_tokens": 30}}),
        latency_ms=100, estimated_cost=0, case=expected,
        analysis_id="aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
    )
    assert actual.failure_category == "FACT_MISMATCH"
    assert not actual.fact_values_valid


def test_invalid_second_model_response_does_not_discard_the_six_case_batch(monkeypatch):
    from io import BytesIO
    cases = [case().model_copy(update={"case_id": f"batch-{i}"}) for i in range(6)]
    requests = []
    def respond(request, **kwargs):
        index = len(requests)
        requests.append(request)
        value = output()
        value["analysisId"] = runner._analysis_id(cases[index])
        if index == 1:
            value["recommendation"] = []
        return BytesIO(json.dumps({"choices": [{"message": {"content": json.dumps(value)}}],
                                  "usage": {"prompt_tokens": 10, "completion_tokens": 5}}).encode())
    monkeypatch.setattr(runner, "urlopen", respond)
    monkeypatch.setenv("COPILOT_INPUT_PRICE_PER_MILLION", "0")
    monkeypatch.setenv("COPILOT_OUTPUT_PRICE_PER_MILLION", "0")
    results = runner.run_real_results(cases, prompt_version="review-analysis-v2", provider="deepseek",
                                      model_id="deepseek-flash", api_key="test-only")
    assert len(results) == 6
    assert results[1].failure_category == "INVALID_SCHEMA"
    assert sum(item.input_tokens for item in results) == 60
    assert sum(item.call_succeeded for item in results) == 5
