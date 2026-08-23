from typing import Literal

from pydantic import BaseModel, Field

Recommendation = Literal[
    "CONFIRMED",
    "IGNORED",
    "INSUFFICIENT_EVIDENCE",
]


class EvaluationCase(BaseModel):
    case_id: str = Field(min_length=1, max_length=100)
    review_task_id: int = Field(gt=0)
    expected_recommendation: Recommendation
