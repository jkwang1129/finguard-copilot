from typing import Any, Literal

from pydantic import BaseModel, Field

Recommendation = Literal[
    "CONFIRMED",
    "IGNORED",
    "INSUFFICIENT_EVIDENCE",
]


class EvaluationCase(BaseModel):
    case_id: str = Field(min_length=1, max_length=100)
    review_task_id: int = Field(gt=0)
    input_facts: dict[str, Any] = Field(default_factory=dict)
    knowledge_evidence: list[dict[str, Any]] = Field(default_factory=list)
    reviewer_input: str = ""
    expected_recommendation: Recommendation
    expected_fact_paths: list[str] = Field(default_factory=list)
    expected_citation_ids: list[str] = Field(default_factory=list)
    is_injection_case: bool = False
    is_insufficient_evidence: bool = False


class ActualAnalysis(BaseModel):
    recommendation: Recommendation | None = None
    fact_paths: list[str] = Field(default_factory=list)
    citation_ids: list[str] = Field(default_factory=list)
    structured_json_valid: bool = True
    latency_ms: float = Field(ge=0)
    estimated_cost: float = Field(ge=0)
    input_tokens: int = Field(default=0, ge=0)
    output_tokens: int = Field(default=0, ge=0)
    call_succeeded: bool = True
    failure_category: str | None = None
    fact_values_valid: bool = False
    citation_valid: bool = False
    analysis_id_valid: bool = False


class CaseMetrics(BaseModel):
    case_id: str
    recommendation_correct: bool
    fact_coverage: float = Field(ge=0, le=1)
    citation_coverage: float = Field(ge=0, le=1)
    json_success: bool
    refusal_hit: bool
    refusal_expected: bool = False
    latency_ms: float = Field(ge=0)
    estimated_cost: float = Field(ge=0)
    input_tokens: int = Field(default=0, ge=0)
    output_tokens: int = Field(default=0, ge=0)
    call_succeeded: bool = True
    failure_category: str | None = None
    fact_values_valid: bool = False
    citation_valid: bool = False
    analysis_id_valid: bool = False
