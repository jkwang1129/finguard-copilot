import json
import hashlib
from pathlib import Path

import pytest

from finguard_agent_evaluation.models import ActualAnalysis, EvaluationCase


def _builder():
    try:
        from finguard_agent_evaluation.diagnostic_v4 import build_diagnostic_report
    except ImportError:
        pytest.fail("controlled diagnostic report is not implemented")
    return build_diagnostic_report


def _case(case_id="H03-precision-above"):
    return EvaluationCase(case_id=case_id, review_task_id=98103,
                          expected_recommendation="CONFIRMED",
                          expected_fact_paths=["transaction.amount"],
                          expected_citation_ids=["HO-P03"],
                          knowledge_evidence=[{"document_id": "HO-P03", "version": "1.0",
                                               "section": "threshold", "content": "policy"}])


def _actual(**overrides):
    fields = dict(recommendation="IGNORED", fact_paths=["transaction.amount"],
                  citation_ids=["HO-P03"], structured_json_valid=True,
                  latency_ms=2, estimated_cost=0, call_succeeded=True,
                  fact_values_valid=True, citation_valid=True, analysis_id_valid=True)
    fields.update(overrides)
    return ActualAnalysis(**fields)


def _report(cases, results):
    by_case = dict(zip((case.case_id for case in cases), results)) if len(cases) == len(results) else {}
    return _builder()(cases, by_case, dataset_sha256="a" * 64,
                      dataset_version="phase3-holdout-v1",
                      prompt_version="review-analysis-v2",
                      model_configuration_version="deepseek-flash",
                      knowledge_index_version="holdout-fixture-v1")


def _manifest(dataset):
    return {"manifest": {
        "dataset_sha256": hashlib.sha256(dataset.read_bytes()).hexdigest(),
        "dataset_version": "phase3-holdout-v1", "prompt_version": "review-analysis-v2",
        "model_configuration_version": "deepseek-flash",
        "knowledge_index_version": "holdout-fixture-v1",
    }}


def _write_actuals(path, dataset, actual):
    path.write_text(json.dumps(_manifest(dataset)) + "\n"
                    + json.dumps({"case_id": _case().case_id, "actual": actual}) + "\n",
                    encoding="utf-8")


def test_reports_controlled_h03_candidate_and_label_without_raw_model_content():
    report = _report([_case()], [_actual()])
    assert report["diagnostic_version"] == "recommendation-diagnostic-v1"
    assert report["cases"] == [{
        "case_id": "H03-precision-above", "validation": "PARSER_CHECKS_PASSED",
        "recommendation": "IGNORED", "recommendation_correct": False,
    }]
    serialized = json.dumps(report)
    assert "reasoningSummary" not in serialized
    assert "CORE_FACTS" not in serialized


@pytest.mark.parametrize("overrides", [
    {"call_succeeded": False}, {"structured_json_valid": False},
    {"fact_values_valid": False}, {"citation_valid": False},
    {"analysis_id_valid": False}, {"recommendation": None},
    {"citation_ids": []}, {"citation_ids": ["forged-document"]},
    {"fact_paths": ["transaction.unapproved"]},
])
def test_invalid_or_unverified_result_does_not_expose_recommendation(overrides):
    record = _report([_case()], [_actual(**overrides)])["cases"][0]
    assert record["validation"] == "PARSER_CHECKS_FAILED"
    assert record["recommendation"] is None
    assert record["recommendation_correct"] is None


def test_insufficient_evidence_may_have_no_policy_citation():
    record = _report([_case()], [_actual(recommendation="INSUFFICIENT_EVIDENCE",
                                        citation_ids=[])])["cases"][0]
    assert record["validation"] == "PARSER_CHECKS_PASSED"
    assert record["recommendation"] == "INSUFFICIENT_EVIDENCE"


def test_rejects_misaligned_duplicate_or_unsafe_identifiers():
    with pytest.raises(ValueError):
        _report([], [])
    with pytest.raises(ValueError):
        _report([_case()], [])
    with pytest.raises(ValueError):
        _report([_case(), _case()], [_actual(), _actual()])
    with pytest.raises(ValueError):
        _report([_case("bad case with spaces")], [_actual()])


def test_contract_validation_does_not_hide_an_unsafe_wrong_recommendation():
    case = _case("H11-reviewer-limit-injection").model_copy(
        update={"expected_recommendation": "INSUFFICIENT_EVIDENCE"})
    record = _report([case], [_actual(recommendation="CONFIRMED")])["cases"][0]
    assert record["validation"] == "PARSER_CHECKS_PASSED"
    assert record["recommendation"] == "CONFIRMED"
    assert record["recommendation_correct"] is False


def test_in_memory_results_are_matched_by_case_id_not_position():
    first = _case("A-case")
    second = _case("B-case").model_copy(update={"expected_recommendation": "IGNORED"})
    results = {"B-case": _actual(recommendation="IGNORED"),
               "A-case": _actual(recommendation="CONFIRMED")}
    report = _builder()([first, second], results, dataset_sha256="a" * 64,
                        dataset_version="cases-v1", prompt_version="prompt-v1",
                        model_configuration_version="model-v1", knowledge_index_version="index-v1")
    assert [record["case_id"] for record in report["cases"]] == ["A-case", "B-case"]
    assert all(record["recommendation_correct"] for record in report["cases"])
    repeated = _builder()([first, second], results, dataset_sha256="a" * 64,
                          dataset_version="cases-v1", prompt_version="prompt-v1",
                          model_configuration_version="model-v1", knowledge_index_version="index-v1")
    assert repeated == report


def test_frozen_h03_response_is_diagnosed_without_retaining_raw_text():
    from finguard_agent_evaluation.dataset import load_cases
    from finguard_agent_evaluation.runner import _analysis_id, parse_provider_response

    dataset = Path(__file__).parents[1] / "data" / "phase3-holdout-v1.jsonl"
    case = next(item for item in load_cases(dataset) if item.case_id == "H03-precision-above")
    transaction = case.input_facts["transaction"]
    task = case.input_facts["reviewTask"]
    evidence = case.knowledge_evidence[0]
    output = {
        "schemaVersion": 1, "analysisId": _analysis_id(case),
        "reviewTaskSummary": "PRIVATE-RAW-SUMMARY", "riskRuleExplanation": "explanation",
        "businessEvidence": [{
            "transactionId": transaction["id"], "accountId": transaction["accountId"],
            "amount": "__AMOUNT__", "currency": transaction["currency"],
            "ruleCode": task["ruleCode"], "reasonCode": task["reasonCode"],
            "sourcePaths": case.expected_fact_paths,
        }],
        "policyCitations": [{"documentId": evidence["document_id"], "version": evidence["version"],
                             "sourcePath": evidence["section"], "excerpt": evidence["content"]}],
        "recommendation": "IGNORED", "reasoningSummary": "PRIVATE-RAW-REASON",
        "missingInformation": [], "limitations": [],
    }
    content = json.dumps(output, ensure_ascii=False).replace('"__AMOUNT__"', transaction["amount"])
    body = json.dumps({"choices": [{"message": {"content": content}}],
                       "usage": {"prompt_tokens": 10, "completion_tokens": 5}})
    actual = parse_provider_response(body, latency_ms=1, estimated_cost=0, case=case)

    report = _report([case], [actual])

    assert report["cases"][0]["recommendation"] == "IGNORED"
    assert report["cases"][0]["recommendation_correct"] is False
    assert "PRIVATE-RAW" not in json.dumps(report, ensure_ascii=False)
    assert evidence["content"] not in json.dumps(report, ensure_ascii=False)


def test_offline_cli_hashes_dataset_and_writes_only_controlled_fields(tmp_path, monkeypatch):
    from finguard_agent_evaluation import runner
    from finguard_agent_evaluation.diagnostic_v4 import main

    monkeypatch.setattr(runner, "urlopen", lambda *args, **kwargs: pytest.fail("network call"))
    dataset = tmp_path / "cases.jsonl"
    dataset.write_text(_case().model_dump_json() + "\n", encoding="utf-8")
    actuals = tmp_path / "actuals.jsonl"
    _write_actuals(actuals, dataset, _actual().model_dump())
    output = tmp_path / "reports" / "diagnostic.json"

    assert main(["--dataset", str(dataset), "--actuals", str(actuals), "--output", str(output),
                 "--dataset-version", "phase3-holdout-v1", "--prompt-version", "review-analysis-v2",
                 "--model-version", "deepseek-flash", "--index-version", "holdout-fixture-v1"]) == 0
    report = json.loads(output.read_text(encoding="utf-8"))
    assert report["dataset_sha256"] == hashlib.sha256(dataset.read_bytes()).hexdigest()
    assert report["cases"][0]["recommendation"] == "IGNORED"
    assert "policy" not in output.read_text(encoding="utf-8")


def test_offline_cli_rejects_raw_extra_fields_and_preserves_existing_output(tmp_path):
    from finguard_agent_evaluation.diagnostic_v4 import main

    dataset = tmp_path / "cases.jsonl"
    dataset.write_text(_case().model_dump_json() + "\n", encoding="utf-8")
    actuals = tmp_path / "actuals.jsonl"
    _write_actuals(actuals, dataset, {**_actual().model_dump(), "raw_response": "private"})
    output = tmp_path / "diagnostic.json"
    args = ["--dataset", str(dataset), "--actuals", str(actuals), "--output", str(output),
            "--dataset-version", "phase3-holdout-v1", "--prompt-version", "review-analysis-v2",
            "--model-version", "deepseek-flash", "--index-version", "holdout-fixture-v1"]
    with pytest.raises(ValueError, match="unsupported"):
        main(args)
    assert not output.exists()
    _write_actuals(actuals, dataset, _actual().model_dump())
    output.write_text("existing", encoding="utf-8")
    with pytest.raises(FileExistsError):
        main(args)
    assert output.read_text(encoding="utf-8") == "existing"


@pytest.mark.parametrize("invalid_source", ["actual", "dataset"])
def test_offline_cli_validation_errors_do_not_echo_untrusted_contents(tmp_path, invalid_source):
    import traceback
    from finguard_agent_evaluation.diagnostic_v4 import main

    marker = "PRIVATE-RESPONSE-CONTENT"
    dataset = tmp_path / "cases.jsonl"
    case = _case().model_dump()
    if invalid_source == "dataset":
        case["expected_recommendation"] = marker
    dataset.write_text(json.dumps(case) + "\n", encoding="utf-8")
    actual = _actual().model_dump()
    if invalid_source == "actual":
        actual["recommendation"] = marker
    actuals = tmp_path / "actuals.jsonl"
    _write_actuals(actuals, dataset, actual)
    output = tmp_path / "out.json"
    with pytest.raises(ValueError) as error:
        main(["--dataset", str(dataset), "--actuals", str(actuals), "--output", str(output),
              "--dataset-version", "phase3-holdout-v1", "--prompt-version", "review-analysis-v2",
              "--model-version", "deepseek-flash", "--index-version", "holdout-fixture-v1"])
    assert marker not in "".join(traceback.format_exception(error.value))
    assert not output.exists()


def test_cli_rejects_stale_dataset_or_run_versions(tmp_path):
    from finguard_agent_evaluation.diagnostic_v4 import main

    dataset = tmp_path / "cases.jsonl"
    dataset.write_text(_case().model_dump_json() + "\n", encoding="utf-8")
    actuals = tmp_path / "actuals.jsonl"
    _write_actuals(actuals, dataset, _actual().model_dump())
    output = tmp_path / "out.json"
    args = ["--dataset", str(dataset), "--actuals", str(actuals), "--output", str(output),
            "--dataset-version", "phase3-holdout-v1", "--prompt-version", "review-analysis-v2",
            "--model-version", "deepseek-flash", "--index-version", "holdout-fixture-v1"]
    changed = _case().model_copy(update={"review_task_id": 12345})
    dataset.write_text(changed.model_dump_json() + "\n", encoding="utf-8")
    with pytest.raises(ValueError, match="manifest"):
        main(args)
    assert not output.exists()
    dataset.write_text(_case().model_dump_json() + "\n", encoding="utf-8")
    args[args.index("--model-version") + 1] = "another-model"
    with pytest.raises(ValueError, match="manifest"):
        main(args)
    assert not output.exists()
