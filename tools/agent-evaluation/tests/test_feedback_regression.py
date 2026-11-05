import hashlib
import json
from pathlib import Path
from uuid import uuid4

import pytest

from finguard_agent_evaluation import phase4_cli, phase4_reference_runner
from finguard_agent_evaluation.feedback_regression import freeze


def write_rows(path, *rows):
    path.write_text("".join(json.dumps(row) + "\n" for row in rows), encoding="utf-8")
    return path


@pytest.fixture
def review_inputs():
    case = json.loads((Path(__file__).parents[1] / "data/phase4-holdout-v1.jsonl")
                      .read_text(encoding="utf-8").splitlines()[0])
    selected = {"analysis_id": str(uuid4()), "status": "SUCCEEDED", "verdict": "DISAGREE"}
    other = {"analysis_id": str(uuid4()), "status": "SUCCEEDED", "verdict": "AGREE"}
    return selected, other, {"analysis_id": selected["analysis_id"], "case": case}


def test_filtered_feedback_replays_against_separately_curated_label(tmp_path, review_inputs):
    selected, other, approval = review_inputs
    feedback = write_rows(tmp_path / "feedback.jsonl", other, selected)
    approvals = write_rows(tmp_path / "approved.jsonl", approval)
    output = tmp_path / "regression"
    manifest = freeze(feedback, approvals, output, "fixture")
    dataset = output / "cases.jsonl"
    raw = dataset.read_bytes()
    assert manifest["approved_count"] == manifest["unselected_count"] == 1
    assert manifest["selected_verdict_counts"] == {"DISAGREE": 1}
    assert manifest["source"] == "fixture"
    assert manifest["dataset_sha256"] == hashlib.sha256(raw).hexdigest()
    assert json.loads(raw) == approval["case"]
    assert all(row["analysis_id"].encode() not in raw for row in (selected, other))

    outcomes = tmp_path / "outcomes.jsonl"
    report = tmp_path / "report.json"
    phase4_reference_runner.main(["--dataset", str(dataset), "--dataset-sha256", manifest["dataset_sha256"],
                                  "--dataset-version", "fixture-v1", "--output", str(outcomes)])
    phase4_cli.main(["--dataset", str(dataset), "--outcomes", str(outcomes), "--output", str(report),
                     "--dataset-version", "fixture-v1", "--prompt-version", "none", "--model-version", "none",
                     "--index-version", "synthetic-v1", "--rule-version", "synthetic-single-threshold-v1",
                     "--source", "langgraph-offline"])
    assert json.loads(report.read_bytes())["accuracy_total"] == {"correct": 1, "total": 1}
    with pytest.raises(FileExistsError):
        freeze(feedback, approvals, output, "fixture")
    assert dataset.read_bytes() == raw


@pytest.mark.parametrize("problem", ["unknown", "duplicate", "extra-field", "pending", "free-text"])
def test_rejects_invalid_feedback_and_approvals_without_output(tmp_path, review_inputs, problem):
    selected, other, approval = review_inputs
    rows = [selected, other]
    if problem == "unknown":
        rows = [other]
    elif problem == "duplicate":
        rows.append(selected)
    elif problem == "extra-field":
        selected["reviewer_username"] = "fixture-reviewer"
    elif problem == "pending":
        selected["status"] = "GENERATING"
    else:
        approval["case"]["amount"] = "raw analyst note"
    feedback = write_rows(tmp_path / "feedback.jsonl", *rows)
    approvals = write_rows(tmp_path / "approved.jsonl", approval)
    output = tmp_path / "regression"
    with pytest.raises(ValueError):
        freeze(feedback, approvals, output, "fixture")
    assert not output.exists()
