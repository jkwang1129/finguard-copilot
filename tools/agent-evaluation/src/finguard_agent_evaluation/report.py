import json
from pathlib import Path

from finguard_agent_evaluation.metrics import EvaluationReport, evaluate_case, summarize
from finguard_agent_evaluation.models import ActualAnalysis, EvaluationCase


def build_report(
    cases: list[EvaluationCase],
    actual: list[ActualAnalysis],
    *,
    dataset_version: str,
    prompt_version: str,
    model_configuration_version: str,
    knowledge_index_version: str,
) -> EvaluationReport:
    if len(cases) != len(actual):
        raise ValueError("cases and actual results must have the same length")
    results = [evaluate_case(case, result) for case, result in zip(cases, actual, strict=True)]
    return summarize(
        results,
        dataset_version=dataset_version,
        prompt_version=prompt_version,
        model_configuration_version=model_configuration_version,
        knowledge_index_version=knowledge_index_version,
    )


def write_report(report: EvaluationReport, output: Path) -> None:
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(
        json.dumps(report.model_dump(mode="json"), ensure_ascii=False, indent=2, sort_keys=True) + "\n",
        encoding="utf-8",
    )


def merge_reports(reports: list[EvaluationReport]) -> dict:
    if not reports:
        raise ValueError("at least one report is required")
    reference = reports[0]
    for report in reports[1:]:
        if (report.dataset_version, report.prompt_version, report.knowledge_index_version) != (
            reference.dataset_version, reference.prompt_version, reference.knowledge_index_version
        ):
            raise ValueError("reports must share dataset, prompt, and knowledge versions")
    return {
        "dataset_version": reference.dataset_version,
        "prompt_version": reference.prompt_version,
        "knowledge_index_version": reference.knowledge_index_version,
        "models": [
            report.model_dump(mode="json")
            for report in sorted(reports, key=lambda item: item.model_configuration_version)
        ],
    }


def compare_prompt_reports(baseline: EvaluationReport, candidate: EvaluationReport) -> dict:
    def identity(report):
        return (report.dataset_version, report.knowledge_index_version,
                report.model_configuration_version, report.dataset_sha256,
                report.output_schema_sha256, report.evaluator_version,
                tuple(item.case_id for item in report.cases))
    if (not baseline.dataset_sha256 or not baseline.output_schema_sha256
            or identity(baseline) != identity(candidate)):
        raise ValueError("prompt reports must use the same inputs, model and evaluator")
    fields = ("recommendation_accuracy", "fact_coverage", "fact_value_accuracy",
              "citation_coverage", "citation_exact_match_rate", "analysis_id_match_rate",
              "json_success_rate", "refusal_hit_rate", "failure_rate", "p50_latency_ms",
              "p95_latency_ms", "total_input_tokens", "total_output_tokens")
    return {
        "scope": "paired six-case synthetic development evaluation, not production quality",
        "total_calls": baseline.sample_count + candidate.sample_count,
        "baseline": baseline.model_dump(mode="json"),
        "candidate": candidate.model_dump(mode="json"),
        "deltas": {field: round(getattr(candidate, field) - getattr(baseline, field), 8)
                   for field in fields},
    }
