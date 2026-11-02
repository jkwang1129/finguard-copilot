import hashlib
import json

import pytest

from finguard_agent_evaluation.phase4_judge import calibrate, main


def test_calibration_counts_abstention_missing_and_disagreement_in_full_denominator():
    labels = {"a": "CONFIRMED", "b": "IGNORED", "c": "INSUFFICIENT_EVIDENCE"}
    outputs = {"a": "CONFIRMED", "b": "ABSTAIN"}
    report = calibrate(labels, outputs, judge_version="judge-v1", label_version="human-v1")
    assert report["agreement"] == {"matched": 1, "total": 3}
    assert report["counts"] == {"matched": 1, "disagreed": 0, "abstained": 1, "missing": 1}
    assert report["cases"] == [{"case_id": "a", "status": "MATCHED"},
                               {"case_id": "b", "status": "ABSTAINED"},
                               {"case_id": "c", "status": "MISSING"}]


@pytest.mark.parametrize("labels,outputs,judge_version,label_version", [
    ({}, {}, "j1", "l1"),
    ({"a": "CONFIRMED"}, {"a": "BAD"}, "j1", "l1"),
    ({"a": "CONFIRMED"}, {"a": "CONFIRMED", "z": "CONFIRMED"}, "j1", "l1"),
    ({"a": "CONFIRMED"}, {}, "bad version", "l1"),
    ({"a": "CONFIRMED"}, {}, "j1", ""),
    ({"a": ["CONFIRMED"]}, {}, "j1", "l1"),
    ({"a": "CONFIRMED"}, {"a": ["CONFIRMED"]}, "j1", "l1"),
])
def test_calibration_rejects_invalid_inputs(labels, outputs, judge_version, label_version):
    with pytest.raises(ValueError):
        calibrate(labels, outputs, judge_version=judge_version, label_version=label_version)


def test_calibration_cli_requires_bound_versions_and_counts_missing(tmp_path):
    dataset = tmp_path / "cases.jsonl"
    dataset.write_text('{"case_id":"a","expected":"IGNORED"}\n'
                       '{"case_id":"b","expected":"CONFIRMED"}\n', encoding="utf-8")
    digest = hashlib.sha256(dataset.read_bytes()).hexdigest()
    labels = tmp_path / "labels.jsonl"
    labels.write_text(json.dumps({"manifest": {"dataset_sha256": digest,
        "label_version": "human-v1"}}) + "\n" +
        '{"case_id":"a","label":"IGNORED"}\n'
        '{"case_id":"b","label":"CONFIRMED"}\n', encoding="utf-8")
    judge = tmp_path / "judge.jsonl"
    judge.write_text(json.dumps({"manifest": {"dataset_sha256": digest,
        "judge_version": "judge-v1"}}) + "\n" +
        '{"case_id":"a","verdict":"IGNORED"}\n', encoding="utf-8")
    output = tmp_path / "calibration.json"
    args = ["--dataset", str(dataset), "--labels", str(labels), "--judge", str(judge),
            "--output", str(output), "--label-version", "human-v1", "--judge-version", "judge-v1"]
    assert main(args) == 0
    report = json.loads(output.read_text(encoding="utf-8"))
    assert report["agreement"] == {"matched": 1, "total": 2}
    assert report["label_verification"] == "unverified"
    with pytest.raises(FileExistsError):
        main(args)
    judge.write_text(judge.read_text(encoding="utf-8").replace("judge-v1", "wrong"), encoding="utf-8")
    with pytest.raises(ValueError):
        main(args)
    judge.write_text(judge.read_text(encoding="utf-8").replace("wrong", "judge-v1")
                     .replace('"verdict":"IGNORED"', '"verdict":{}'), encoding="utf-8")
    with pytest.raises(ValueError):
        main(args)
