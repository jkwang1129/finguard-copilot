from finguard_agent_evaluation.report import build_report, merge_reports
from finguard_agent_evaluation.models import ActualAnalysis, EvaluationCase


def _report(model_version: str):
    case = EvaluationCase(
        case_id="case-001",
        review_task_id=1,
        expected_recommendation="CONFIRMED",
    )
    return build_report(
        [case],
        [ActualAnalysis(recommendation="CONFIRMED", latency_ms=10, estimated_cost=0.001)],
        dataset_version="phase3-golden-v1",
        prompt_version="review-analysis-v1",
        model_configuration_version=model_version,
        knowledge_index_version="published-v1",
    )


def test_merge_reports_requires_shared_versions_and_sorts_candidates():
    merged = merge_reports([_report("zhipu:glm-5.2"), _report("qwen:qwen-plus")])

    assert merged["dataset_version"] == "phase3-golden-v1"
    assert merged["prompt_version"] == "review-analysis-v1"
    assert merged["knowledge_index_version"] == "published-v1"
    assert [item["model_configuration_version"] for item in merged["models"]] == [
        "qwen:qwen-plus", "zhipu:glm-5.2"
    ]
    assert "SYSTEM_POLICY" not in str(merged)
