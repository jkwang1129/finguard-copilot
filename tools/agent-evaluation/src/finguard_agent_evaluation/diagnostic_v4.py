"""Controlled, deterministic recommendation diagnostics for new evaluation runs.

The caller must provide ActualAnalysis from the frozen v3 parser with a case and
analysis ID. This module does not contact a provider or retain response text.
"""

import argparse
import hashlib
import json
import re
from pathlib import Path

from pydantic import ValidationError

from finguard_agent_evaluation.models import ActualAnalysis, EvaluationCase
from finguard_agent_evaluation.runner import ALLOWED_SOURCE_PATHS

_IDENTIFIER = re.compile(r"[A-Za-z0-9][A-Za-z0-9._:-]{0,127}\Z")
_SHA256 = re.compile(r"[0-9a-f]{64}\Z")
_RECOMMENDATIONS = frozenset({"CONFIRMED", "IGNORED", "INSUFFICIENT_EVIDENCE"})


def build_diagnostic_report(
    cases: list[EvaluationCase],
    results: dict[str, ActualAnalysis],
    *,
    dataset_sha256: str,
    dataset_version: str,
    prompt_version: str,
    model_configuration_version: str,
    knowledge_index_version: str,
) -> dict:
    """Return only version identifiers and validated, bounded recommendation enums."""
    if not isinstance(dataset_sha256, str) or not _SHA256.fullmatch(dataset_sha256):
        raise ValueError("dataset_sha256 must be a lowercase SHA-256")
    versions = (dataset_version, prompt_version, model_configuration_version, knowledge_index_version)
    if any(not isinstance(value, str) or not _IDENTIFIER.fullmatch(value) for value in versions):
        raise ValueError("diagnostic version identifier is invalid")
    if not cases or not isinstance(results, dict):
        raise ValueError("cases must be non-empty and results keyed by case ID")
    if any(not isinstance(case, EvaluationCase) or not _IDENTIFIER.fullmatch(case.case_id)
           for case in cases):
        raise ValueError("diagnostic case identifier is invalid")
    if len({case.case_id for case in cases}) != len(cases):
        raise ValueError("diagnostic case identifiers must be unique")
    if set(results) != {case.case_id for case in cases}:
        raise ValueError("diagnostic results must match dataset case IDs")
    if any(not isinstance(result, ActualAnalysis) for result in results.values()):
        raise ValueError("diagnostic result is invalid")

    records = []
    for case in cases:
        result = results[case.case_id]
        available_documents = {item["document_id"] for item in case.knowledge_evidence
                               if isinstance(item.get("document_id"), str)}
        recommendation = result.recommendation
        validated = (
            result.call_succeeded is True
            and result.structured_json_valid is True
            and result.fact_values_valid is True
            and result.citation_valid is True
            and result.analysis_id_valid is True
            and isinstance(recommendation, str)
            and recommendation in _RECOMMENDATIONS
            and bool(result.fact_paths)
            and set(result.fact_paths) <= ALLOWED_SOURCE_PATHS
            and set(result.citation_ids) <= available_documents
            and (recommendation == "INSUFFICIENT_EVIDENCE" or bool(result.citation_ids))
        )
        records.append({
            "case_id": case.case_id,
            "validation": "PARSER_CHECKS_PASSED" if validated else "PARSER_CHECKS_FAILED",
            "recommendation": recommendation if validated else None,
            "recommendation_correct": (
                recommendation == case.expected_recommendation if validated else None
            ),
        })

    return {
        "diagnostic_version": "recommendation-diagnostic-v1",
        "dataset_sha256": dataset_sha256,
        "dataset_version": dataset_version,
        "prompt_version": prompt_version,
        "model_configuration_version": model_configuration_version,
        "knowledge_index_version": knowledge_index_version,
        "cases": sorted(records, key=lambda item: item["case_id"]),
    }


def main(argv: list[str] | None = None) -> int:
    """Read sanitized v3 results and write a new report without contacting a provider."""
    parser = argparse.ArgumentParser(description="Build an offline recommendation diagnostic")
    parser.add_argument("--dataset", type=Path, required=True)
    parser.add_argument("--actuals", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--dataset-version", required=True)
    parser.add_argument("--prompt-version", required=True)
    parser.add_argument("--model-version", required=True)
    parser.add_argument("--index-version", required=True)
    args = parser.parse_args(argv)

    dataset_bytes = args.dataset.read_bytes()
    try:
        cases = [EvaluationCase.model_validate_json(line) for line in dataset_bytes.decode("utf-8").splitlines()
                 if line.strip()]
    except (UnicodeDecodeError, ValidationError):
        raise ValueError("invalid diagnostic dataset") from None
    try:
        lines = args.actuals.read_text(encoding="utf-8").splitlines()
    except UnicodeDecodeError:
        raise ValueError("invalid diagnostic input") from None
    if not lines:
        raise ValueError("diagnostic input manifest is missing")
    try:
        header = json.loads(lines[0])
    except json.JSONDecodeError:
        raise ValueError("invalid diagnostic input manifest") from None
    expected_manifest = {
        "dataset_sha256": hashlib.sha256(dataset_bytes).hexdigest(),
        "dataset_version": args.dataset_version,
        "prompt_version": args.prompt_version,
        "model_configuration_version": args.model_version,
        "knowledge_index_version": args.index_version,
    }
    if not isinstance(header, dict) or set(header) != {"manifest"} or header["manifest"] != expected_manifest:
        raise ValueError("diagnostic input manifest does not match the requested run")
    actuals = {}
    for line in lines[1:]:
        try:
            entry = json.loads(line)
        except json.JSONDecodeError:
            raise ValueError("invalid diagnostic input") from None
        if not isinstance(entry, dict) or set(entry) != {"case_id", "actual"}:
            raise ValueError("unsupported diagnostic input fields")
        actual = entry["actual"]
        if not isinstance(actual, dict) or set(actual) - set(ActualAnalysis.model_fields):
            raise ValueError("unsupported diagnostic actual fields")
        case_id = entry["case_id"]
        if not isinstance(case_id, str) or case_id in actuals:
            raise ValueError("diagnostic input case identifier is invalid")
        try:
            actuals[case_id] = ActualAnalysis.model_validate(actual)
        except ValidationError:
            raise ValueError("invalid diagnostic actual") from None
    if len({case.case_id for case in cases}) != len(cases) or set(actuals) != {case.case_id for case in cases}:
        raise ValueError("diagnostic inputs must match the dataset case IDs exactly")
    report = build_diagnostic_report(
        cases, actuals,
        dataset_sha256=hashlib.sha256(dataset_bytes).hexdigest(),
        dataset_version=args.dataset_version, prompt_version=args.prompt_version,
        model_configuration_version=args.model_version, knowledge_index_version=args.index_version,
    )
    args.output.parent.mkdir(parents=True, exist_ok=True)
    with args.output.open("x", encoding="utf-8") as output:
        output.write(json.dumps(report, ensure_ascii=False, indent=2, sort_keys=True) + "\n")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
