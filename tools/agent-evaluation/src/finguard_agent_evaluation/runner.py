import argparse
import hashlib
import json
import math
import os
import sys
import time
import uuid
from decimal import Decimal, InvalidOperation
from pathlib import Path
from urllib.error import HTTPError, URLError
from urllib.request import Request, urlopen

from finguard_agent_evaluation.dataset import load_cases
from finguard_agent_evaluation.models import ActualAnalysis, EvaluationCase
from finguard_agent_evaluation.metrics import EvaluationReport
from finguard_agent_evaluation.report import build_report, compare_prompt_reports, merge_reports, write_report

REQUIRED_REAL_ENV = (
    "FINGUARD_COPILOT_BASE_URL",
    "COPILOT_MODEL_PROVIDER",
    "COPILOT_MODEL_ID",
    "COPILOT_MODEL_API_KEY",
    "COPILOT_PRICE_TABLE_VERSION",
    "COPILOT_INPUT_PRICE_PER_MILLION",
    "COPILOT_OUTPUT_PRICE_PER_MILLION",
)

PROVIDER_ENDPOINTS = {
    "qwen": "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions",
    "deepseek": "https://api.deepseek.com/chat/completions",
    "zhipu": "https://open.bigmodel.cn/api/paas/v4/chat/completions",
}

SCHEMA_PATH = Path(__file__).resolve().parents[4] / "src/main/resources/schema/analysis-output-v1.schema.json"
ALLOWED_SOURCE_PATHS = frozenset(json.loads(SCHEMA_PATH.read_text(encoding="utf-8"))[
    "properties"]["businessEvidence"]["items"]["properties"]["sourcePaths"]["items"]["enum"])


def validate_real_cases(cases: list[EvaluationCase]) -> None:
    """Reject unanswerable fixtures before spending any provider quota."""
    seen = set()
    for case in cases:
        if case.case_id in seen:
            raise ValueError("duplicate case id")
        seen.add(case.case_id)
        for path in ALLOWED_SOURCE_PATHS:
            parent, field = path.split(".")
            value = case.input_facts.get(parent, {}).get(field)
            if value is None or (isinstance(value, str) and not value.strip()):
                raise ValueError(f"{case.case_id}: missing {path}")
            if field in {"id", "accountId"} and (type(value) is not int or value < 1):
                raise ValueError(f"{case.case_id}: invalid {path}")
            if field in {"currency", "ruleCode", "reasonCode"} and not _non_blank_text(value):
                raise ValueError(f"{case.case_id}: invalid {path}")
        try:
            amount = Decimal(str(case.input_facts["transaction"]["amount"]))
        except InvalidOperation:
            raise ValueError(f"{case.case_id}: invalid transaction.amount") from None
        if not amount.is_finite() or amount < 0:
            raise ValueError(f"{case.case_id}: invalid transaction.amount")
        if (not set(case.expected_fact_paths) <= ALLOWED_SOURCE_PATHS
                or len(set(case.expected_fact_paths)) != len(case.expected_fact_paths)):
            raise ValueError(f"{case.case_id}: unsupported expected source path")
        documents = set()
        for evidence in case.knowledge_evidence:
            if not all(_non_blank_text(evidence.get(field))
                       for field in ("document_id", "version", "section", "content")):
                raise ValueError(f"{case.case_id}: incomplete policy citation")
            documents.add(evidence["document_id"])
        if not set(case.expected_citation_ids) <= documents:
            raise ValueError(f"{case.case_id}: expected citation not supplied")


def _analysis_id(case: EvaluationCase) -> str:
    return str(uuid.uuid5(uuid.NAMESPACE_URL, "urn:finguard:quality:" + case.case_id))


def _citation(evidence: dict) -> dict:
    return {"documentId": evidence["document_id"], "version": evidence["version"],
            "sourcePath": evidence["section"], "excerpt": evidence["content"]}


def _fact_values_match(parsed: dict, case: EvaluationCase) -> bool:
    transaction = case.input_facts["transaction"]
    task = case.input_facts["reviewTask"]
    return all(
        item["transactionId"] == transaction["id"]
        and item["accountId"] == transaction["accountId"]
        and Decimal(str(item["amount"])) == Decimal(str(transaction["amount"]))
        and item["currency"] == transaction["currency"]
        and item["ruleCode"] == task["ruleCode"]
        and item["reasonCode"] == task["reasonCode"]
        for item in parsed["businessEvidence"]
    )


def missing_real_environment(environ: dict[str, str] | None = None) -> list[str]:
    values = os.environ if environ is None else environ
    return [name for name in REQUIRED_REAL_ENV if not values.get(name)]


def provider_endpoint(provider: str) -> str:
    try:
        return PROVIDER_ENDPOINTS[provider.strip().lower()]
    except (AttributeError, KeyError) as error:
        raise ValueError("unsupported model provider") from error


def parse_provider_response(
    body: str,
    *,
    latency_ms: float,
    estimated_cost: float,
    case: EvaluationCase | None = None,
    analysis_id: str | None = None,
) -> ActualAnalysis:
    try:
        response = json.loads(body)
    except (TypeError, ValueError, json.JSONDecodeError):
        return _failed_result_with_usage(latency_ms, estimated_cost, "INVALID_RESPONSE")

    try:
        message = response["choices"][0]["message"]
        usage = response.get("usage", {})
        input_tokens = _non_negative_int(usage.get("prompt_tokens", 0))
        output_tokens = _non_negative_int(usage.get("completion_tokens", 0))
        content = _content_text(message["content"])
    except (KeyError, IndexError, TypeError, ValueError):
        return _failed_result_with_usage(latency_ms, estimated_cost, "INVALID_RESPONSE")

    try:
        parsed = json.loads(_unwrap_code_fence(content), parse_float=Decimal)
    except (TypeError, ValueError, json.JSONDecodeError):
        return _failed_result_with_usage(
            latency_ms, estimated_cost, "INVALID_OUTPUT", input_tokens, output_tokens)
    if not isinstance(parsed, dict) or not _is_analysis_shape(parsed):
        return _failed_result_with_usage(
            latency_ms, estimated_cost, "INVALID_SCHEMA", input_tokens, output_tokens)
    recommendation = parsed.get("recommendation")
    if recommendation not in {"CONFIRMED", "IGNORED", "INSUFFICIENT_EVIDENCE"}:
        return _failed_result_with_usage(
            latency_ms, estimated_cost, "INVALID_SCHEMA", input_tokens, output_tokens)
    evidence = parsed["businessEvidence"]
    citations = parsed["policyCitations"]
    fact_paths = [path for item in evidence for path in item["sourcePaths"]]
    citation_ids = [item["documentId"] for item in citations]
    checks = {}
    if case is not None:
        checks = {
            "analysis_id_valid": parsed["analysisId"] == (analysis_id or _analysis_id(case)),
            "fact_values_valid": _fact_values_match(parsed, case),
            "citation_valid": all(item in [_citation(e) for e in case.knowledge_evidence]
                                  for item in citations),
        }
        category = next((category for check, category in (
            (checks["analysis_id_valid"], "ANALYSIS_ID_MISMATCH"),
            (checks["fact_values_valid"], "FACT_MISMATCH"),
            (checks["citation_valid"], "CITATION_MISMATCH"),
            (recommendation == "INSUFFICIENT_EVIDENCE" or bool(citations), "CITATION_REQUIRED"),
        ) if not check), None)
        if category:
            return ActualAnalysis(
                recommendation=None, structured_json_valid=True, call_succeeded=False,
                failure_category=category, input_tokens=input_tokens, output_tokens=output_tokens,
                latency_ms=latency_ms, estimated_cost=estimated_cost, **checks,
            )
    return ActualAnalysis(
        recommendation=recommendation,
        fact_paths=fact_paths,
        citation_ids=citation_ids,
        structured_json_valid=True,
        latency_ms=latency_ms,
        estimated_cost=estimated_cost,
        input_tokens=input_tokens,
        output_tokens=output_tokens,
        **checks,
    )


def run_real_results(cases, *, prompt_version: str, provider: str, model_id: str,
                     api_key: str, max_cases: int = 0) -> list[ActualAnalysis]:
    endpoint = provider_endpoint(provider)
    prompt = evaluation_prompt(prompt_version)
    selected = cases if max_cases <= 0 else cases[:max_cases]
    validate_real_cases(selected)
    results = []
    input_price = _price_from_environment("COPILOT_INPUT_PRICE_PER_MILLION")
    output_price = _price_from_environment("COPILOT_OUTPUT_PRICE_PER_MILLION")
    timeout = float(os.environ.get("COPILOT_MODEL_TIMEOUT_SECONDS", "20"))
    for case in selected:
        payload = {
            "model": model_id,
            "messages": [
                {"role": "system", "content": prompt},
                {"role": "user", "content": _case_context(case)},
            ],
            "max_tokens": 2000,
            "temperature": 0.01 if provider.lower() == "zhipu" else 0,
            "response_format": {"type": "json_object"},
        }
        if provider.lower() == "deepseek":
            payload["thinking"] = {"type": "disabled"}
        started = time.perf_counter()
        request = Request(
            endpoint,
            data=json.dumps(payload, ensure_ascii=False).encode("utf-8"),
            headers={
                "Authorization": "Bearer " + api_key,
                "Content-Type": "application/json",
            },
            method="POST",
        )
        try:
            with urlopen(request, timeout=timeout) as response:
                body = response.read().decode("utf-8")
            latency_ms = round((time.perf_counter() - started) * 1000, 3)
            parsed = parse_provider_response(body, latency_ms=latency_ms, estimated_cost=0.0,
                                             case=case, analysis_id=_analysis_id(case))
            results.append(_with_cost(parsed, input_price, output_price))
        except HTTPError as error:
            results.append(_failed_result(started, "RATE_LIMITED" if error.code == 429 else "UNAVAILABLE"))
        except TimeoutError:
            results.append(_failed_result(started, "TIMEOUT"))
        except URLError:
            results.append(_failed_result(started, "UNAVAILABLE"))
        print(json.dumps({"case_id": case.case_id, "prompt_version": prompt_version,
                          "call_succeeded": results[-1].call_succeeded,
                          "failure_category": results[-1].failure_category}), flush=True)
    if len(results) != len(selected):
        raise RuntimeError("real evaluation did not produce one result per selected case")
    return results


def stub_results(cases) -> list[ActualAnalysis]:
    return [
        ActualAnalysis(
            recommendation=case.expected_recommendation,
            fact_paths=case.expected_fact_paths,
            citation_ids=case.expected_citation_ids,
            structured_json_valid=True,
            latency_ms=1.0,
            estimated_cost=0.0,
            input_tokens=0,
            output_tokens=0,
        )
        for case in cases
    ]


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Offline and guarded Phase 3 evaluation runner")
    parser.add_argument("--mode", choices=("stub", "real", "merge", "compare-prompts"), required=True)
    parser.add_argument("--dataset", type=Path)
    parser.add_argument("--prompt-version")
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--reports", type=Path, nargs="+")
    parser.add_argument("--dataset-version", default="phase3-golden-v1")
    parser.add_argument("--knowledge-index-version", default="published-v1")
    parser.add_argument("--model-configuration-version", default="stub-v1")
    parser.add_argument("--max-cases", type=int, default=0)
    args = parser.parse_args(argv)

    if args.mode in {"merge", "compare-prompts"}:
        if not args.reports:
            print("--reports is required for merge mode", file=sys.stderr)
            return 2
        reports = [EvaluationReport.model_validate_json(path.read_text(encoding="utf-8"))
                   for path in args.reports]
        if args.mode == "compare-prompts" and len(reports) != 2:
            parser.error("compare-prompts requires baseline and candidate reports")
        combined = (compare_prompt_reports(*reports) if args.mode == "compare-prompts"
                    else merge_reports(reports))
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(
            json.dumps(combined, ensure_ascii=False, indent=2, sort_keys=True) + "\n",
            encoding="utf-8",
        )
        return 0

    if not args.dataset or not args.prompt_version:
        print("--dataset and --prompt-version are required for stub and real modes", file=sys.stderr)
        return 2

    if args.mode == "real":
        missing = missing_real_environment()
        if missing:
            print("Missing required environment fields: " + ", ".join(missing), file=sys.stderr)
            return 2
        if args.max_cases < 0:
            print("--max-cases must not be negative", file=sys.stderr)
            return 2
        cases = load_cases(args.dataset)
        try:
            results = run_real_results(
                cases,
                prompt_version=args.prompt_version,
                provider=os.environ["COPILOT_MODEL_PROVIDER"],
                model_id=os.environ["COPILOT_MODEL_ID"],
                api_key=os.environ["COPILOT_MODEL_API_KEY"],
                max_cases=args.max_cases,
            )
        except (OSError, ValueError, RuntimeError) as error:
            print("Real model evaluation failed: " + type(error).__name__, file=sys.stderr)
            return 3
        report = build_report(
            cases[:len(results)], results,
            dataset_version=args.dataset_version,
            prompt_version=args.prompt_version,
            model_configuration_version=args.model_configuration_version,
            knowledge_index_version=args.knowledge_index_version,
        )
        report = report.model_copy(update={
            "dataset_sha256": hashlib.sha256(args.dataset.read_bytes()).hexdigest(),
            "prompt_sha256": hashlib.sha256(evaluation_prompt(args.prompt_version).encode()).hexdigest(),
            "output_schema_sha256": hashlib.sha256(SCHEMA_PATH.read_bytes()).hexdigest(),
            "evaluator_version": "exact-facts-citations-v3",
        })
        write_report(report, args.output)
        return 0

    cases = load_cases(args.dataset)
    report = build_report(
        cases,
        stub_results(cases),
        dataset_version=args.dataset_version,
        prompt_version=args.prompt_version,
        model_configuration_version=args.model_configuration_version,
        knowledge_index_version=args.knowledge_index_version,
    )
    write_report(report, args.output)
    return 0


def _non_negative_int(value) -> int:
    if not isinstance(value, int) or value < 0:
        raise ValueError("token usage is invalid")
    return value


def _is_analysis_shape(value: dict) -> bool:
    required = {
        "schemaVersion", "analysisId", "reviewTaskSummary", "riskRuleExplanation",
        "businessEvidence", "policyCitations", "recommendation", "reasoningSummary",
        "missingInformation", "limitations",
    }
    if set(value) != required or type(value["schemaVersion"]) is not int or value["schemaVersion"] != 1:
        return False
    try:
        uuid.UUID(value["analysisId"])
    except (AttributeError, ValueError, TypeError):
        return False
    for field in ("reviewTaskSummary", "riskRuleExplanation", "reasoningSummary"):
        if not _non_blank_text(value[field]):
            return False
    if (not isinstance(value["recommendation"], str)
            or value["recommendation"] not in {"CONFIRMED", "IGNORED", "INSUFFICIENT_EVIDENCE"}):
        return False
    if not _is_string_list(value["missingInformation"]) or not _is_string_list(value["limitations"]):
        return False
    evidence = value["businessEvidence"]
    if not isinstance(evidence, list) or not evidence:
        return False
    evidence_fields = {
        "transactionId", "accountId", "amount", "currency", "ruleCode", "reasonCode", "sourcePaths",
    }
    for item in evidence:
        if not isinstance(item, dict) or set(item) != evidence_fields:
            return False
        if (type(item["transactionId"]) is not int or item["transactionId"] < 1
                or type(item["accountId"]) is not int or item["accountId"] < 1
                or not _is_non_negative_number(item["amount"])
                or not _non_blank_text(item["currency"])
                or not _non_blank_text(item["ruleCode"])
                or not _non_blank_text(item["reasonCode"])
                or not _is_string_list(item["sourcePaths"], non_empty=True)
                or not set(item["sourcePaths"]) <= ALLOWED_SOURCE_PATHS):
            return False
    citations = value["policyCitations"]
    citation_fields = {"documentId", "version", "sourcePath", "excerpt"}
    if not isinstance(citations, list):
        return False
    return all(
        isinstance(item, dict) and set(item) == citation_fields
        and all(_non_blank_text(item[field]) for field in citation_fields)
        for item in citations
    )


def _non_blank_text(value) -> bool:
    return isinstance(value, str) and bool(value.strip())


def _is_string_list(value, *, non_empty: bool = False) -> bool:
    return isinstance(value, list) and (not non_empty or bool(value)) and all(_non_blank_text(item) for item in value)


def _is_non_negative_number(value) -> bool:
    if isinstance(value, Decimal):
        return value.is_finite() and value >= 0
    if type(value) is int:
        return value >= 0
    return type(value) is float and math.isfinite(value) and value >= 0


def _price_from_environment(name: str) -> float:
    value = os.environ.get(name)
    if value is None or not value.strip():
        raise ValueError(f"{name} must be explicitly configured")
    try:
        price = float(value)
    except ValueError as error:
        raise ValueError(f"{name} is invalid") from error
    if not math.isfinite(price) or price < 0:
        raise ValueError(f"{name} is invalid")
    return price


def _unwrap_code_fence(content: str) -> str:
    if not isinstance(content, str) or not content.strip():
        raise ValueError("model content is blank")
    value = content.strip()
    if value.startswith("```") and value.endswith("```"):
        first_line_end = value.find("\n")
        if first_line_end < 0:
            raise ValueError("code fence is malformed")
        language = value[3:first_line_end].strip().lower()
        if language not in {"", "json"}:
            raise ValueError("code fence language is invalid")
        return value[first_line_end + 1:-3].strip()
    return value


def _content_text(content) -> str:
    if isinstance(content, str):
        return content
    if isinstance(content, list):
        parts = []
        for part in content:
            if not isinstance(part, dict) or not isinstance(part.get("text"), str):
                raise ValueError("model content part is invalid")
            parts.append(part["text"])
        return "".join(parts)
    raise ValueError("model content is invalid")


def _failed_result_with_usage(latency_ms: float, estimated_cost: float, category: str,
                              input_tokens: int = 0, output_tokens: int = 0) -> ActualAnalysis:
    return ActualAnalysis(
        recommendation=None,
        structured_json_valid=False,
        latency_ms=latency_ms,
        estimated_cost=estimated_cost,
        input_tokens=input_tokens,
        output_tokens=output_tokens,
        call_succeeded=False,
        failure_category=category,
    )


def _load_prompt(version: str) -> str:
    path = Path(__file__).resolve().parents[4] / "src" / "main" / "resources" / "prompts" / f"{version}.txt"
    return path.read_text(encoding="utf-8")


def evaluation_prompt(version: str) -> str:
    return _load_prompt(version) + "\nOUTPUT_SCHEMA\n" + SCHEMA_PATH.read_text(encoding="utf-8")


def _case_context(case) -> str:
    return "ANALYSIS_REQUEST\n" + json.dumps({"analysisId": _analysis_id(case)}, sort_keys=True) \
        + "\nCORE_FACTS\n" + json.dumps(case.input_facts, ensure_ascii=False, sort_keys=True) \
        + "\nKNOWLEDGE_EVIDENCE\n" + json.dumps(
            [{"citation": _citation(e)} for e in case.knowledge_evidence], ensure_ascii=False, sort_keys=True) \
        + "\nREVIEWER_INPUT\n" + case.reviewer_input


def _with_cost(actual: ActualAnalysis, input_price: float, output_price: float) -> ActualAnalysis:
    cost = (actual.input_tokens * input_price + actual.output_tokens * output_price) / 1_000_000
    return actual.model_copy(update={"estimated_cost": round(cost, 8)})


def _failed_result(started: float, category: str) -> ActualAnalysis:
    return ActualAnalysis(
        recommendation=None,
        structured_json_valid=False,
        latency_ms=round((time.perf_counter() - started) * 1000, 3),
        estimated_cost=0.0,
        call_succeeded=False,
        failure_category=category,
    )


if __name__ == "__main__":
    raise SystemExit(main())
