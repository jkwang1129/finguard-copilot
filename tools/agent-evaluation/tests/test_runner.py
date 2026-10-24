import json

import pytest

from finguard_agent_evaluation.runner import (
    evaluation_prompt,
    main,
    missing_real_environment,
    parse_provider_response,
    provider_endpoint,
)


def test_real_mode_reports_missing_field_names_without_values(capsys, tmp_path):
    exit_code = main([
        "--mode", "real", "--dataset", str(tmp_path / "unused.jsonl"),
        "--prompt-version", "review-analysis-v1", "--output", str(tmp_path / "out.json"),
    ])

    captured = capsys.readouterr()
    assert exit_code == 2
    assert "COPILOT_MODEL_API_KEY" in captured.err
    assert "secret" not in captured.err.lower()
    assert missing_real_environment({"COPILOT_MODEL_API_KEY": "secret"}) == [
        "FINGUARD_COPILOT_BASE_URL", "COPILOT_MODEL_PROVIDER", "COPILOT_MODEL_ID",
        "COPILOT_PRICE_TABLE_VERSION", "COPILOT_INPUT_PRICE_PER_MILLION",
        "COPILOT_OUTPUT_PRICE_PER_MILLION",
    ]


def test_stub_mode_writes_reproducible_non_secret_report(tmp_path):
    output = tmp_path / "reports" / "stub.json"
    dataset = tmp_path / "cases.jsonl"
    dataset.write_text(json.dumps({
        "case_id": "case-001", "review_task_id": 1, "expected_recommendation": "CONFIRMED",
    }) + "\n", encoding="utf-8")

    assert main([
        "--mode", "stub", "--dataset", str(dataset), "--prompt-version", "review-analysis-v1",
        "--output", str(output),
    ]) == 0
    report = json.loads(output.read_text(encoding="utf-8"))
    assert report["sample_count"] == 1
    assert report["model_configuration_version"] == "stub-v1"
    assert "SYSTEM_POLICY" not in output.read_text(encoding="utf-8")


def test_provider_endpoint_uses_fixed_allowlist():
    assert provider_endpoint("qwen") == "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions"
    assert provider_endpoint("zhipu") == "https://open.bigmodel.cn/api/paas/v4/chat/completions"

    with pytest.raises(ValueError, match="unsupported model provider"):
        provider_endpoint("http://attacker.example")


def test_evaluation_prompt_contains_the_published_output_schema():
    prompt = evaluation_prompt("review-analysis-v1")

    assert "OUTPUT_SCHEMA" in prompt
    assert '"businessEvidence"' in prompt
    assert '"INSUFFICIENT_EVIDENCE"' in prompt


def test_parse_provider_response_returns_sanitized_actual_analysis():
    response = {
        "choices": [{
            "message": {
                "content": json.dumps({
                    "schemaVersion": 1,
                    "analysisId": "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
                    "reviewTaskSummary": "summary",
                    "riskRuleExplanation": "explanation",
                    "recommendation": "CONFIRMED",
                    "businessEvidence": [{
                        "transactionId": 11, "accountId": 42, "amount": 12500.0,
                        "currency": "USD", "ruleCode": "RULE_HIGH_AMOUNT",
                        "reasonCode": "REASON_AMOUNT_THRESHOLD",
                        "sourcePaths": ["transaction.amount"]
                    }],
                    "policyCitations": [{
                        "documentId": "policy-001", "version": "v1",
                        "sourcePath": "thresholds", "excerpt": "policy"
                    }],
                    "reasoningSummary": "reason",
                    "missingInformation": [],
                    "limitations": []
                })
            }
        }],
        "usage": {"prompt_tokens": 120, "completion_tokens": 30},
    }

    actual = parse_provider_response(json.dumps(response), latency_ms=240.0, estimated_cost=0.0012)

    assert actual.recommendation == "CONFIRMED"
    assert actual.fact_paths == ["transaction.amount"]
    assert actual.citation_ids == ["policy-001"]
    assert actual.input_tokens == 120
    assert actual.output_tokens == 30
    assert actual.call_succeeded is True


def test_parse_provider_response_accepts_text_parts_and_keeps_usage_on_invalid_output():
    valid_output = {
        "schemaVersion": 1,
        "analysisId": "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
        "reviewTaskSummary": "summary",
        "riskRuleExplanation": "explanation",
        "businessEvidence": [{
            "transactionId": 11, "accountId": 42, "amount": 1.0,
            "currency": "USD", "ruleCode": "RULE", "reasonCode": "REASON",
            "sourcePaths": ["transaction.amount"],
        }],
        "policyCitations": [],
        "recommendation": "IGNORED",
        "reasoningSummary": "reason",
        "missingInformation": [],
        "limitations": [],
    }
    valid_response = {
        "choices": [{"message": {"content": [{"text": json.dumps(valid_output)}]}}],
        "usage": {"prompt_tokens": 7, "completion_tokens": 3},
    }
    valid = parse_provider_response(json.dumps(valid_response), latency_ms=20.0, estimated_cost=0.0)
    assert valid.recommendation == "IGNORED"
    assert valid.input_tokens == 7
    assert valid.output_tokens == 3

    invalid_response = {
        "choices": [{"message": {"content": "not-json"}}],
        "usage": {"prompt_tokens": 8, "completion_tokens": 4},
    }
    invalid = parse_provider_response(json.dumps(invalid_response), latency_ms=20.0, estimated_cost=0.0)
    assert invalid.input_tokens == 8
    assert invalid.output_tokens == 4


def test_parse_provider_response_classifies_invalid_output_without_preserving_body():
    response = {
        "choices": [{"message": {"content": "not-json-with-provider-details"}}],
        "usage": {"prompt_tokens": 2, "completion_tokens": 1},
    }

    actual = parse_provider_response(json.dumps(response), latency_ms=10.0, estimated_cost=0.0)

    assert actual.recommendation is None
    assert actual.structured_json_valid is False
    assert actual.call_succeeded is False
    assert actual.failure_category == "INVALID_OUTPUT"
    assert "provider-details" not in actual.model_dump_json()


def test_parse_provider_response_classifies_invalid_schema_separately():
    response = {
        "choices": [{"message": {"content": json.dumps({"recommendation": "maybe"})}}],
        "usage": {"prompt_tokens": 2, "completion_tokens": 1},
    }

    actual = parse_provider_response(json.dumps(response), latency_ms=10.0, estimated_cost=0.0)

    assert actual.failure_category == "INVALID_SCHEMA"


def test_parse_provider_response_rejects_partial_object_as_invalid_schema():
    response = {
        "choices": [{"message": {"content": json.dumps({"recommendation": "CONFIRMED"})}}],
        "usage": {"prompt_tokens": 2, "completion_tokens": 1},
    }

    actual = parse_provider_response(json.dumps(response), latency_ms=10.0, estimated_cost=0.0)

    assert actual.recommendation is None
    assert actual.structured_json_valid is False
    assert actual.failure_category == "INVALID_SCHEMA"


def test_merge_mode_combines_reports_without_prompt_content(tmp_path):
    first = tmp_path / "qwen.json"
    second = tmp_path / "zhipu.json"
    output = tmp_path / "comparison.json"
    dataset = tmp_path / "cases.jsonl"
    dataset.write_text(json.dumps({
        "case_id": "case-001", "review_task_id": 1, "expected_recommendation": "CONFIRMED",
    }) + "\n", encoding="utf-8")

    for path, model in ((first, "qwen:qwen-plus"), (second, "zhipu:glm-5.2")):
        assert main([
            "--mode", "stub", "--dataset", str(dataset), "--prompt-version", "review-analysis-v1",
            "--model-configuration-version", model, "--output", str(path),
        ]) == 0

    assert main(["--mode", "merge", "--reports", str(second), str(first), "--output", str(output)]) == 0
    merged = json.loads(output.read_text(encoding="utf-8"))
    assert [item["model_configuration_version"] for item in merged["models"]] == [
        "qwen:qwen-plus", "zhipu:glm-5.2"
    ]
