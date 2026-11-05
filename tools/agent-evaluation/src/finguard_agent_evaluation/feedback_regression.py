"""Freeze curator-approved, sanitized feedback cases for the existing Phase 4 runner."""

import argparse
import hashlib
import json
import re
from collections import Counter
from pathlib import Path
from tempfile import TemporaryDirectory
from uuid import UUID

from finguard_agent_evaluation.phase4_cli import decode_records
from finguard_agent_evaluation.phase4_reference import run_case

VERDICTS = frozenset({"AGREE", "DISAGREE", "UNSURE"})
SYNTHETIC_AMOUNT = re.compile(r"(?:0|[1-9][0-9]*)(?:\.[0-9]+)?\Z")


def analysis_id(record: dict) -> str:
    identifier = record.get("analysis_id")
    try:
        if str(UUID(identifier)) == identifier:
            return identifier
    except (ValueError, AttributeError, TypeError):
        pass
    raise ValueError("Expected a canonical analysis UUID")


def freeze(feedback: Path, approved: Path, output: Path, source: str) -> dict:
    if source not in {"fixture", "reviewer-export"}:
        raise ValueError("Unknown feedback source")
    feedback_bytes = feedback.read_bytes()
    approval_bytes = approved.read_bytes()
    verdicts = {}
    for row in decode_records(feedback_bytes):
        if (not isinstance(row, dict) or set(row) != {"analysis_id", "status", "verdict"}
                or row["status"] != "SUCCEEDED" or not isinstance(row["verdict"], str)
                or row["verdict"] not in VERDICTS):
            raise ValueError("Feedback export must contain only completed analyses and categorical verdicts")
        identifier = analysis_id(row)
        if identifier in verdicts:
            raise ValueError("Duplicate feedback analysis")
        verdicts[identifier] = row["verdict"]

    cases = {}
    selected = set()
    counts = Counter()
    for row in decode_records(approval_bytes):
        if not isinstance(row, dict) or set(row) != {"analysis_id", "case"} or not isinstance(row["case"], dict):
            raise ValueError("Approval must contain only the analysis UUID and a sanitized regression case")
        identifier = analysis_id(row)
        if identifier not in verdicts or identifier in selected:
            raise ValueError("Approval must match a unique exported feedback row")
        case = row["case"]
        run_case(case)  # Validate the case shape without deriving the reviewed label from feedback.
        if case["amount"] is not None and not SYNTHETIC_AMOUNT.fullmatch(case["amount"]):
            raise ValueError("Use a synthetic decimal amount or null")
        if case["case_id"] in cases:
            raise ValueError("Duplicate regression case ID")
        cases[case["case_id"]] = case
        selected.add(identifier)
        counts[verdicts[identifier]] += 1

    data = "".join(json.dumps(cases[key], ensure_ascii=False, sort_keys=True, allow_nan=False) + "\n"
                   for key in sorted(cases)).encode("utf-8")
    manifest = {
        "schema_version": "feedback-regression-v1",
        "source": source,
        "feedback_sha256": hashlib.sha256(feedback_bytes).hexdigest(),
        "approval_sha256": hashlib.sha256(approval_bytes).hexdigest(),
        "dataset_sha256": hashlib.sha256(data).hexdigest(),
        "approved_count": len(cases),
        "unselected_count": len(verdicts) - len(selected),
        "selected_verdict_counts": dict(counts),
    }
    output = output.resolve()
    if output.exists():
        raise FileExistsError("Use a new regression output directory")
    output.parent.mkdir(parents=True, exist_ok=True)
    with TemporaryDirectory(prefix=".feedback-", dir=output.parent) as temporary:
        staged = Path(temporary)
        (staged / "cases.jsonl").write_bytes(data)
        (staged / "manifest.json").write_text(json.dumps(manifest, sort_keys=True, indent=2) + "\n", encoding="utf-8")
        staged.rename(output)
    return manifest


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ("feedback", "approved", "output"):
        parser.add_argument(f"--{name}", required=True, type=Path)
    parser.add_argument("--source", required=True, choices=("fixture", "reviewer-export"))
    args = parser.parse_args()
    print(json.dumps(freeze(args.feedback, args.approved, args.output, args.source), sort_keys=True))


if __name__ == "__main__":
    main()
