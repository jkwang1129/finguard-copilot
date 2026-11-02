"""Offline calibration of externally supplied bounded Judge outputs."""

import re
import argparse
import hashlib
import json
from pathlib import Path

_ID = re.compile(r"[A-Za-z0-9][A-Za-z0-9._:-]{0,127}\Z")
_LABELS = {"CONFIRMED", "IGNORED", "INSUFFICIENT_EVIDENCE"}


def calibrate(labels: dict[str, str], outputs: dict[str, str], *,
              judge_version: str, label_version: str) -> dict:
    """Compare supplied labels and Judge decisions; does not validate label expertise."""
    if (not isinstance(labels, dict) or not labels or not isinstance(outputs, dict)
            or any(not isinstance(value, str) or not _ID.fullmatch(value)
                   for value in (judge_version, label_version))
            or any(not isinstance(key, str) or not _ID.fullmatch(key)
                   or not isinstance(value, str) or value not in _LABELS
                   for key, value in labels.items())
            or set(outputs) - set(labels)
            or any(not isinstance(value, str) or value not in _LABELS | {"ABSTAIN"}
                   for value in outputs.values())):
        raise ValueError("invalid calibration input")
    counts = {"matched": 0, "disagreed": 0, "abstained": 0, "missing": 0}
    cases = []
    for case_id in sorted(labels):
        if case_id not in outputs:
            kind = "missing"
        elif outputs[case_id] == "ABSTAIN":
            kind = "abstained"
        elif outputs[case_id] == labels[case_id]:
            kind = "matched"
        else:
            kind = "disagreed"
        counts[kind] += 1
        cases.append({"case_id": case_id, "status": kind.upper()})
    return {"judge_version": judge_version, "label_version": label_version,
            "label_verification": "unverified", "judge_output_verification": "unverified",
            "agreement": {"matched": counts["matched"], "total": len(labels)},
            "counts": counts, "cases": cases}


def _read_jsonl(path: Path) -> list[dict]:
    try:
        lines = path.read_text(encoding="utf-8").splitlines()
        if not lines or any(not line.strip() for line in lines):
            raise ValueError("empty judge input")
        return [json.loads(line) for line in lines]
    except (UnicodeDecodeError, json.JSONDecodeError) as error:
        raise ValueError("invalid judge JSONL") from error


def _indexed(records: list[dict], key: str) -> dict[str, str]:
    values = {}
    for record in records:
        if (not isinstance(record, dict) or set(record) != {"case_id", key}
                or not isinstance(record["case_id"], str) or record["case_id"] in values
                or not isinstance(record[key], str)):
            raise ValueError("invalid or duplicate calibration row")
        values[record["case_id"]] = record[key]
    return values


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Calibrate bounded Judge outputs offline")
    for name in ("dataset", "labels", "judge", "output"):
        parser.add_argument("--" + name, type=Path, required=True)
    parser.add_argument("--label-version", required=True)
    parser.add_argument("--judge-version", required=True)
    args = parser.parse_args(argv)
    digest = hashlib.sha256(args.dataset.read_bytes()).hexdigest()
    dataset_rows = _read_jsonl(args.dataset)
    ids = [row.get("case_id") for row in dataset_rows if isinstance(row, dict)]
    if (len(ids) != len(dataset_rows) or len(ids) != len(set(ids))
            or any(not isinstance(case_id, str) or not _ID.fullmatch(case_id) for case_id in ids)):
        raise ValueError("invalid calibration dataset IDs")
    label_rows = _read_jsonl(args.labels)
    judge_rows = _read_jsonl(args.judge)
    if (label_rows[0] != {"manifest": {"dataset_sha256": digest,
                                       "label_version": args.label_version}}
            or judge_rows[0] != {"manifest": {"dataset_sha256": digest,
                                              "judge_version": args.judge_version}}):
        raise ValueError("calibration manifest mismatch")
    labels = _indexed(label_rows[1:], "label")
    outputs = _indexed(judge_rows[1:], "verdict")
    if set(labels) != set(ids):
        raise ValueError("human labels must cover every selected calibration case")
    report = calibrate(labels, outputs, judge_version=args.judge_version,
                       label_version=args.label_version)
    report["dataset_sha256"] = digest
    args.output.parent.mkdir(parents=True, exist_ok=True)
    with args.output.open("x", encoding="utf-8") as stream:
        stream.write(json.dumps(report, ensure_ascii=False, indent=2, sort_keys=True) + "\n")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
