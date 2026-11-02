from pathlib import Path
import hashlib

import pytest

from finguard_agent_evaluation.phase4_holdout import validate_holdout


DATA = Path(__file__).parents[1] / "data" / "phase4-holdout-v1.jsonl"
SHA = "eb9146eb4c2325018b27963a2c62e0ce6a8847d00032a6fe3b91e325aaa5d3d2"


def test_frozen_synthetic_holdout_has_unique_self_consistent_cases():
    result = validate_holdout(DATA, SHA, 20)
    assert result["count"] == 20
    assert result["label_counts"] == {"CONFIRMED": 6, "IGNORED": 5,
                                      "INSUFFICIENT_EVIDENCE": 9}


def test_rejects_empty_duplicate_bad_label_missing_fields_and_hash(tmp_path):
    source = DATA.read_text(encoding="utf-8")
    cases = tmp_path / "bad.jsonl"
    for content in ("", source.splitlines()[0] + "\n" + source.splitlines()[0] + "\n",
                    source.replace('"expected":"CONFIRMED"', '"expected":"BAD"', 1),
                    source.replace('"category":"threshold_equal",', "", 1)):
        cases.write_text(content, encoding="utf-8")
        with pytest.raises(ValueError):
            validate_holdout(cases, hashlib.sha256(cases.read_bytes()).hexdigest(), 20)
    with pytest.raises(ValueError):
        validate_holdout(DATA, "0" * 64, 20)
