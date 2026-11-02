"""Deterministic summary of caller-supplied, non-sensitive case outcomes."""

import re

_ID = re.compile(r"[A-Za-z0-9][A-Za-z0-9._:-]{0,127}\Z")
_HASH = re.compile(r"[a-f0-9]{64}\Z")
_RECOMMENDATIONS = {"CONFIRMED", "IGNORED", "INSUFFICIENT_EVIDENCE"}


def build_report(cases: list[dict], results: dict, dataset_sha256: str, dataset_version: str) -> dict:
    """Summarize reported outcomes; no input authenticity or business validity is asserted."""
    if (not isinstance(dataset_sha256, str) or not _HASH.fullmatch(dataset_sha256)
            or not isinstance(dataset_version, str) or not _ID.fullmatch(dataset_version)
            or not isinstance(cases, list) or not cases or not isinstance(results, dict)):
        raise ValueError("invalid report input")
    expected = {}
    for case in cases:
        if (not isinstance(case, dict) or set(case) != {"case_id", "expected"}
                or not isinstance(case["case_id"], str) or not _ID.fullmatch(case["case_id"])
                or case["case_id"] in expected or not isinstance(case["expected"], str)
                or case["expected"] not in _RECOMMENDATIONS):
            raise ValueError("invalid report case")
        expected[case["case_id"]] = case["expected"]
    if set(results) - set(expected):
        raise ValueError("result does not belong to dataset")
    records = []
    counts = {"completed": 0, "failed": 0, "not_run": 0}
    refused = 0
    for case_id in sorted(expected):
        result = results.get(case_id, {"status": "not_run"})
        if not isinstance(result, dict):
            raise ValueError("invalid result")
        status = result.get("status")
        if status == "completed":
            if (set(result) != {"status", "recommendation"} or not isinstance(result["recommendation"], str)
                    or result["recommendation"] not in _RECOMMENDATIONS):
                raise ValueError("invalid completed result")
            match = result["recommendation"] == expected[case_id]
            if result["recommendation"] == "INSUFFICIENT_EVIDENCE":
                refused += 1
        elif isinstance(status, str) and status in {"failed", "not_run"} and set(result) == {"status"}:
            match = None
        else:
            raise ValueError("invalid result status")
        counts[status] += 1
        records.append({"case_id": case_id, "status": status, "matches_label": match})
    return {"dataset_sha256": dataset_sha256, "dataset_version": dataset_version,
            "version_verification": "unverified", "outcome_verification": "unverified",
            "counts": counts, "refused": refused, "cases": records}
