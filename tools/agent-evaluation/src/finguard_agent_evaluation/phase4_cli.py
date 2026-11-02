"""Offline, sanitized Phase 4 report generation; no model or Core calls."""

import argparse
import hashlib
import json
import re
from pathlib import Path

from finguard_agent_evaluation.phase4_report import build_report
from finguard_agent_evaluation.phase4_reference import run_case

_ID = re.compile(r"[A-Za-z0-9][A-Za-z0-9._:-]{0,127}\Z")


def _records(path: Path) -> list[dict]:
    try:
        lines = path.read_text(encoding="utf-8").splitlines()
        if not lines or any(not line.strip() for line in lines):
            raise ValueError("empty or blank JSONL record")
        records = [json.loads(line) for line in lines]
    except (UnicodeDecodeError, json.JSONDecodeError) as error:
        raise ValueError("invalid JSONL input") from error
    return records


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Build a sanitized Phase 4 offline report")
    for name in ("dataset", "outcomes", "output"):
        parser.add_argument("--" + name, required=True, type=Path)
    for name in ("dataset-version", "prompt-version", "model-version", "index-version",
                 "rule-version", "source"):
        parser.add_argument("--" + name, required=True)
    args = parser.parse_args(argv)
    versions = (args.dataset_version, args.prompt_version, args.model_version,
                args.index_version, args.rule_version, args.source)
    if any(not _ID.fullmatch(value) for value in versions):
        raise ValueError("invalid version/source identifier")
    digest = hashlib.sha256(args.dataset.read_bytes()).hexdigest()
    cases = _records(args.dataset)
    projected = []
    for case in cases:
        if not isinstance(case, dict):
            raise ValueError("invalid dataset case")
        if set(case) == {"case_id", "expected"}:
            projected.append(case)
        else:
            run_case(case)  # strict synthetic case schema; does not validate label correctness
            projected.append({"case_id": case["case_id"], "expected": case["expected"]})
    records = _records(args.outcomes)
    manifest = {"dataset_sha256": digest, "dataset_version": args.dataset_version,
                "prompt_version": args.prompt_version, "model_version": args.model_version,
                "index_version": args.index_version, "rule_version": args.rule_version,
                "source": args.source}
    if not isinstance(records[0], dict) or records[0] != {"manifest": manifest}:
        raise ValueError("outcome manifest does not match read dataset and declared versions")
    results = {}
    for record in records[1:]:
        if (not isinstance(record, dict) or set(record) != {"case_id", "result"}
                or not isinstance(record["case_id"], str) or record["case_id"] in results):
            raise ValueError("invalid or duplicate outcome case")
        results[record["case_id"]] = record["result"]
    report = build_report(projected, results, digest, args.dataset_version)
    report["manifest"] = manifest
    report["accuracy_total"] = {"correct": sum(item["matches_label"] is True
                                                 for item in report["cases"]),
                                "total": len(report["cases"])}
    report["bad_cases"] = [{"case_id": item["case_id"],
                            "reason": "MISMATCH" if item["matches_label"] is False
                            else item["status"].upper()}
                           for item in report["cases"] if item["matches_label"] is not True]
    args.output.parent.mkdir(parents=True, exist_ok=True)
    with args.output.open("x", encoding="utf-8") as stream:
        stream.write(json.dumps(report, ensure_ascii=False, indent=2, sort_keys=True) + "\n")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
