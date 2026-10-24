from finguard_agent_evaluation.metrics import evaluate_case, summarize
from finguard_agent_evaluation.models import ActualAnalysis, EvaluationCase


def test_evaluate_case_calculates_fact_citation_json_and_refusal_metrics():
    case = EvaluationCase(
        case_id="injection-001",
        review_task_id=1006,
        expected_recommendation="INSUFFICIENT_EVIDENCE",
        expected_fact_paths=["transaction.amount", "reviewTask.ruleCode"],
        expected_citation_ids=["policy-001"],
        is_injection_case=True,
        is_insufficient_evidence=True,
    )
    actual = ActualAnalysis(
        recommendation="INSUFFICIENT_EVIDENCE",
        fact_paths=["transaction.amount", "reviewTask.ruleCode"],
        citation_ids=[],
        structured_json_valid=True,
        latency_ms=120.0,
        estimated_cost=0.0012,
    )

    metrics = evaluate_case(case, actual)

    assert metrics.case_id == "injection-001"
    assert metrics.recommendation_correct is True
    assert metrics.fact_coverage == 1.0
    assert metrics.citation_coverage == 0.0
    assert metrics.json_success is True
    assert metrics.refusal_hit is True


def test_summarize_is_sorted_and_reports_rates_percentiles_and_cost():
    cases = [
        EvaluationCase(case_id="b", review_task_id=2, expected_recommendation="CONFIRMED"),
        EvaluationCase(case_id="a", review_task_id=1, expected_recommendation="IGNORED"),
    ]
    results = [
        evaluate_case(cases[0], ActualAnalysis(
            recommendation="CONFIRMED", input_tokens=100, output_tokens=20,
            latency_ms=200, estimated_cost=0.002)),
        evaluate_case(cases[1], ActualAnalysis(
            recommendation="CONFIRMED", input_tokens=50, output_tokens=10,
            structured_json_valid=False, call_succeeded=False, failure_category="INVALID_OUTPUT",
            latency_ms=100, estimated_cost=0.001)),
    ]

    report = summarize(results, dataset_version="phase3-golden-v1", prompt_version="review-analysis-v1",
                       model_configuration_version="stub-v1", knowledge_index_version="published-v1")

    assert [item.case_id for item in report.cases] == ["a", "b"]
    assert report.sample_count == 2
    assert report.recommendation_accuracy == 0.5
    assert report.json_success_rate == 0.5
    assert report.p50_latency_ms == 100.0
    assert report.p95_latency_ms == 200.0
    assert report.total_estimated_cost == 0.003
    assert report.total_input_tokens == 150
    assert report.total_output_tokens == 30
    assert report.failure_rate == 0.5
    assert "SYSTEM_POLICY" not in report.model_dump_json()


def test_failed_call_is_not_counted_as_refusal_hit_for_non_refusal_case():
    case = EvaluationCase(
        case_id="unavailable-001",
        review_task_id=3,
        expected_recommendation="CONFIRMED",
    )

    metrics = evaluate_case(case, ActualAnalysis(
        latency_ms=20.0,
        estimated_cost=0.0,
        call_succeeded=False,
        failure_category="TIMEOUT",
    ))

    assert metrics.refusal_hit is False


def test_citation_coverage_ignores_citations_not_present_in_supplied_evidence():
    case = EvaluationCase(
        case_id="citation-001",
        review_task_id=4,
        expected_recommendation="CONFIRMED",
        expected_citation_ids=["policy-001"],
        knowledge_evidence=[{"document_id": "policy-002"}],
    )

    metrics = evaluate_case(case, ActualAnalysis(
        recommendation="CONFIRMED",
        citation_ids=["policy-001"],
        latency_ms=1.0,
        estimated_cost=0.0,
    ))

    assert metrics.citation_coverage == 0.0
