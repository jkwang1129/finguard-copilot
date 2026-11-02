"""Run the offline LangGraph reference against a pinned synthetic dataset."""

import argparse
import hashlib
import json
from pathlib import Path

from finguard_agent_evaluation.phase4_reference import run_case


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Run the offline synthetic reference")
    parser.add_argument("--dataset", required=True, type=Path)
    parser.add_argument("--dataset-sha256", required=True)
    parser.add_argument("--dataset-version", required=True)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args(argv)
    raw = args.dataset.read_bytes()
    digest = hashlib.sha256(raw).hexdigest()
    if digest != args.dataset_sha256:
        raise ValueError("dataset hash mismatch")
    try:
        cases = [json.loads(line) for line in raw.decode("utf-8").splitlines()]
    except (UnicodeDecodeError, json.JSONDecodeError) as error:
        raise ValueError("invalid reference dataset") from error
    if not cases or len({case.get("case_id") for case in cases if isinstance(case, dict)}) != len(cases):
        raise ValueError("empty or duplicate reference cases")
    manifest = {"dataset_sha256": digest, "dataset_version": args.dataset_version,
                "prompt_version": "none", "model_version": "none", "index_version": "synthetic-v1",
                "rule_version": "synthetic-single-threshold-v1", "source": "langgraph-offline"}
    outputs = [run_case(case) for case in cases]
    args.output.parent.mkdir(parents=True, exist_ok=True)
    with args.output.open("x", encoding="utf-8") as stream:
        stream.write(json.dumps({"manifest": manifest}, sort_keys=True) + "\n")
        for result in sorted(outputs, key=lambda item: item["case_id"]):
            stream.write(json.dumps(result, sort_keys=True) + "\n")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
