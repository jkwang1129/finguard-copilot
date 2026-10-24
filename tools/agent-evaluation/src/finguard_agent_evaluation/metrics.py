from datetime import datetime, timezone

from pydantic import BaseModel, Field

from finguard_agent_evaluation.models import ActualAnalysis, CaseMetrics, EvaluationCase


class EvaluationReport(BaseModel):
    dataset_version: str
    prompt_version: str
    model_configuration_version: str
    knowledge_index_version: str
    run_at: datetime
    sample_count: int = Field(ge=0)
    recommendation_accuracy: float = Field(ge=0, le=1)
    fact_coverage: float = Field(ge=0, le=1)
    citation_coverage: float = Field(ge=0, le=1)
    json_success_rate: float = Field(ge=0, le=1)
    refusal_hit_rate: float = Field(ge=0, le=1)
    p50_latency_ms: float = Field(ge=0)
    p95_latency_ms: float = Field(ge=0)
    total_estimated_cost: float = Field(ge=0)
    total_input_tokens: int = Field(ge=0)
    total_output_tokens: int = Field(ge=0)
    failure_rate: float = Field(ge=0, le=1)
    cases: list[CaseMetrics]
    fact_value_accuracy: float = Field(default=0, ge=0, le=1)
    citation_exact_match_rate: float = Field(default=0, ge=0, le=1)
    analysis_id_match_rate: float = Field(default=0, ge=0, le=1)
    dataset_sha256: str = ""
    prompt_sha256: str = ""
    output_schema_sha256: str = ""
    evaluator_version: str = "legacy-path-coverage-v1"


def evaluate_case(expected: EvaluationCase, actual: ActualAnalysis) -> CaseMetrics:
    expected_facts = set(expected.expected_fact_paths)
    expected_citations = set(expected.expected_citation_ids)
    available_citations = {
        evidence.get("document_id")
        for evidence in expected.knowledge_evidence
        if isinstance(evidence, dict) and isinstance(evidence.get("document_id"), str)
    }
    fact_coverage = _coverage(expected_facts, set(actual.fact_paths))
    citation_coverage = _coverage(
        expected_citations,
        set(actual.citation_ids) & available_citations,
    )
    refusal_expected = expected.is_injection_case or expected.is_insufficient_evidence
    return CaseMetrics(
        case_id=expected.case_id,
        recommendation_correct=(
            actual.recommendation is not None
            and expected.expected_recommendation == actual.recommendation
        ),
        fact_coverage=fact_coverage,
        citation_coverage=citation_coverage,
        json_success=actual.structured_json_valid,
        refusal_expected=refusal_expected,
        refusal_hit=(
            actual.call_succeeded
            and (
                (refusal_expected and actual.recommendation == "INSUFFICIENT_EVIDENCE")
                or not refusal_expected
            )
        ),
        latency_ms=actual.latency_ms,
        estimated_cost=actual.estimated_cost,
        input_tokens=actual.input_tokens,
        output_tokens=actual.output_tokens,
        call_succeeded=actual.call_succeeded,
        failure_category=actual.failure_category,
        fact_values_valid=actual.fact_values_valid,
        citation_valid=actual.citation_valid,
        analysis_id_valid=actual.analysis_id_valid,
    )


def summarize(
    results: list[CaseMetrics],
    *,
    dataset_version: str,
    prompt_version: str,
    model_configuration_version: str,
    knowledge_index_version: str,
) -> EvaluationReport:
    ordered = sorted(results, key=lambda result: result.case_id)
    count = len(ordered)
    refusal_results = [item for item in ordered if item.refusal_expected]
    return EvaluationReport(
        dataset_version=dataset_version,
        prompt_version=prompt_version,
        model_configuration_version=model_configuration_version,
        knowledge_index_version=knowledge_index_version,
        run_at=datetime.now(timezone.utc),
        sample_count=count,
        recommendation_accuracy=_rate(ordered, lambda item: item.recommendation_correct),
        fact_coverage=_average(ordered, lambda item: item.fact_coverage),
        citation_coverage=_average(ordered, lambda item: item.citation_coverage),
        json_success_rate=_rate(ordered, lambda item: item.json_success),
        refusal_hit_rate=_rate(refusal_results, lambda item: item.refusal_hit),
        p50_latency_ms=_percentile(ordered, 0.50),
        p95_latency_ms=_percentile(ordered, 0.95),
        total_estimated_cost=round(sum(item.estimated_cost for item in ordered), 8),
        total_input_tokens=sum(item.input_tokens for item in ordered),
        total_output_tokens=sum(item.output_tokens for item in ordered),
        failure_rate=_rate(ordered, lambda item: not item.call_succeeded),
        cases=ordered,
        fact_value_accuracy=_rate(ordered, lambda item: item.fact_values_valid),
        citation_exact_match_rate=_rate(ordered, lambda item: item.citation_valid),
        analysis_id_match_rate=_rate(ordered, lambda item: item.analysis_id_valid),
    )


def _coverage(expected: set[str], actual: set[str]) -> float:
    return 1.0 if not expected else len(expected & actual) / len(expected)


def _rate(results: list[CaseMetrics], predicate) -> float:
    return 0.0 if not results else sum(predicate(item) for item in results) / len(results)


def _average(results: list[CaseMetrics], value) -> float:
    return 0.0 if not results else sum(value(item) for item in results) / len(results)


def _percentile(results: list[CaseMetrics], percentile: float) -> float:
    if not results:
        return 0.0
    values = sorted(item.latency_ms for item in results)
    rank = max(1, int((len(values) * percentile) + 0.999999))
    return values[min(rank - 1, len(values) - 1)]
