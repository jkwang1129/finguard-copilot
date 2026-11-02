import hashlib
import json

import pytest

from finguard_agent_evaluation.phase4_reference_runner import main


def test_reference_runner_emits_bound_manifest_and_no_labels(tmp_path):
    source = tmp_path / "cases.jsonl"
    source.write_text(json.dumps({"case_id": "S1", "category": "boundary",
        "rule_code": "SYNTH_LIMIT", "currency": "USD", "amount": "100.00",
        "operator": "GTE", "threshold": "100.00", "policy_document_id": "SYNTH_POLICY_V1",
        "citation_ids": ["SYNTH_POLICY_V1"], "expected": "CONFIRMED"}) + "\n", encoding="utf-8")
    digest = hashlib.sha256(source.read_bytes()).hexdigest()
    output = tmp_path / "results.jsonl"
    args = ["--dataset", str(source), "--dataset-sha256", digest,
            "--output", str(output), "--dataset-version", "test-v1"]
    assert main(args) == 0
    records = [json.loads(line) for line in output.read_text(encoding="utf-8").splitlines()]
    assert records[0]["manifest"]["dataset_sha256"] == digest
    assert records[1] == {"case_id": "S1", "result":
                          {"status": "completed", "recommendation": "CONFIRMED"}}
    assert "expected" not in output.read_text(encoding="utf-8")
    with pytest.raises(FileExistsError):
        main(args)
    with pytest.raises(ValueError):
        main(args[:args.index("--dataset-sha256") + 1] + ["0" * 64] +
             args[args.index("--output"):])
