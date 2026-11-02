"""Validate and pin the synthetic P4-2 holdout before evaluating outputs."""

import hashlib
import json
from collections import Counter
from pathlib import Path

from finguard_agent_evaluation.phase4_reference import run_case


def validate_holdout(path: Path, expected_sha256: str, expected_count: int) -> dict:
    raw = path.read_bytes()
    actual_hash = hashlib.sha256(raw).hexdigest()
    if actual_hash != expected_sha256 or expected_count < 1:
        raise ValueError("holdout hash or count mismatch")
    try:
        lines = raw.decode("utf-8").splitlines()
        if not lines or any(not line.strip() for line in lines):
            raise ValueError("empty holdout row")
        cases = [json.loads(line) for line in lines]
    except (UnicodeDecodeError, json.JSONDecodeError) as error:
        raise ValueError("invalid holdout encoding or JSON") from error
    if len(cases) != expected_count:
        raise ValueError("holdout count mismatch")
    ids = set()
    categories = Counter()
    labels = Counter()
    for case in cases:
        predicted = run_case(case)
        case_id = case["case_id"]
        if case_id in ids or predicted["result"]["recommendation"] != case["expected"]:
            raise ValueError("duplicate ID or inconsistent synthetic label")
        ids.add(case_id)
        categories[case["category"]] += 1
        labels[case["expected"]] += 1
    return {"sha256": actual_hash, "count": len(cases),
            "category_counts": dict(sorted(categories.items())),
            "label_counts": dict(sorted(labels.items()))}
