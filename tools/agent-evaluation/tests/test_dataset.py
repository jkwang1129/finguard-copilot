import json

import pytest
from pydantic import ValidationError

from finguard_agent_evaluation.dataset import load_cases
from finguard_agent_evaluation.models import EvaluationCase


def test_load_cases_returns_typed_evaluation_case(tmp_path):
    dataset_path = tmp_path / "cases.jsonl"
    dataset_path.write_text(
        json.dumps(
            {
                "case_id": "high-amount-001",
                "review_task_id": 1001,
                "expected_recommendation": "CONFIRMED",
            }
        )
        + "\n",
        encoding="utf-8",
    )

    cases = load_cases(dataset_path)

    assert cases == [
        EvaluationCase(
            case_id="high-amount-001",
            review_task_id=1001,
            expected_recommendation="CONFIRMED",
        )
    ]


def test_load_cases_rejects_unknown_recommendation(tmp_path):
    dataset_path = tmp_path / "invalid.jsonl"
    dataset_path.write_text(
        json.dumps(
            {
                "case_id": "invalid-recommendation",
                "review_task_id": 1002,
                "expected_recommendation": "AUTO_APPROVE",
            }
        )
        + "\n",
        encoding="utf-8",
    )

    with pytest.raises(ValidationError):
        load_cases(dataset_path)
