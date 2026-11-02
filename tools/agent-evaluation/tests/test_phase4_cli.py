import hashlib
import json

import pytest

from finguard_agent_evaluation.phase4_cli import main


def fixture(tmp_path):
    dataset = tmp_path / "cases.jsonl"
    dataset.write_text('{"case_id":"b","expected":"CONFIRMED"}\n'
                       '{"case_id":"a","expected":"INSUFFICIENT_EVIDENCE"}\n', encoding="utf-8")
    digest = hashlib.sha256(dataset.read_bytes()).hexdigest()
    outcomes = tmp_path / "outcomes.jsonl"
    manifest = {"dataset_sha256": digest, "dataset_version": "dev-v1",
                "prompt_version": "p1", "model_version": "stub-v1",
                "index_version": "i1", "rule_version": "r1", "source": "synthetic-stub"}
    outcomes.write_text(json.dumps({"manifest": manifest}) + "\n" +
                        json.dumps({"case_id": "a", "result": {"status": "completed",
                                    "recommendation": "INSUFFICIENT_EVIDENCE"}}) + "\n" +
                        json.dumps({"case_id": "b", "result": {"status": "failed"}}) + "\n",
                        encoding="utf-8")
    args = ["--dataset", str(dataset), "--outcomes", str(outcomes),
            "--output", str(tmp_path / "report.json"), "--dataset-version", "dev-v1",
            "--prompt-version", "p1", "--model-version", "stub-v1",
            "--index-version", "i1", "--rule-version", "r1", "--source", "synthetic-stub"]
    return args, manifest, outcomes


def test_report_recomputes_hash_and_is_stable(tmp_path):
    args, manifest, _ = fixture(tmp_path)
    assert main(args) == 0
    report = json.loads((tmp_path / "report.json").read_text(encoding="utf-8"))
    assert report["manifest"] == manifest
    assert report["counts"] == {"completed": 1, "failed": 1, "not_run": 0}
    assert report["bad_cases"] == [{"case_id": "b", "reason": "FAILED"}]
    assert report["accuracy_total"] == {"correct": 1, "total": 2}
    duplicate_args = args.copy()
    duplicate_args[duplicate_args.index("--output") + 1] = str(tmp_path / "report-again.json")
    assert main(duplicate_args) == 0
    assert (tmp_path / "report.json").read_bytes() == (tmp_path / "report-again.json").read_bytes()
    with pytest.raises(FileExistsError):
        main(args)


def test_report_rejects_manifest_mismatch_and_duplicate_ids(tmp_path):
    args, _, outcomes = fixture(tmp_path)
    args[args.index("--rule-version") + 1] = "different"
    with pytest.raises(ValueError):
        main(args)
    args[args.index("--rule-version") + 1] = "r1"
    with outcomes.open("a", encoding="utf-8") as stream:
        stream.write('{"case_id":"a","result":{"status":"failed"}}\n')
    with pytest.raises(ValueError):
        main(args)


def test_report_rejects_raw_payload(tmp_path):
    args, _, outcomes = fixture(tmp_path)
    contents = outcomes.read_text(encoding="utf-8").replace(
        '"status": "failed"}', '"status": "failed", "raw_response": "secret"}')
    outcomes.write_text(contents, encoding="utf-8")
    with pytest.raises(ValueError):
        main(args)


def test_report_accepts_full_synthetic_case_contract(tmp_path):
    args, _, outcomes = fixture(tmp_path)
    dataset = tmp_path / "cases.jsonl"
    rows = [json.loads(line) for line in dataset.read_text(encoding="utf-8").splitlines()]
    for row in rows:
        row.update(category="control", rule_code="SYNTH_LIMIT", currency="USD", amount="1",
                   operator="GTE", threshold="100.00", policy_document_id="SYNTH_POLICY_V1",
                   citation_ids=["SYNTH_POLICY_V1"])
    dataset.write_text("\n".join(json.dumps(row) for row in rows) + "\n", encoding="utf-8")
    lines = outcomes.read_text(encoding="utf-8").splitlines()
    header = json.loads(lines[0])
    header["manifest"]["dataset_sha256"] = hashlib.sha256(dataset.read_bytes()).hexdigest()
    outcomes.write_text(json.dumps(header) + "\n" + "\n".join(lines[1:]) + "\n", encoding="utf-8")
    assert main(args) == 0
