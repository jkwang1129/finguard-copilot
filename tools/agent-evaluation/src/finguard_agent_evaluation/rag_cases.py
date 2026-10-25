from __future__ import annotations

import json
from pathlib import Path

from pydantic import BaseModel, Field


class RagCase(BaseModel):
    case_id: str = Field(min_length=1)
    query: str = Field(min_length=1)
    expected_chunk_ids: tuple[str, ...] = ()
    required_citation_document_id: str | None = None
    required_citation_document_version: str | None = None
    forbidden_chunk_ids: tuple[str, ...] = ()

    def __init__(self, **data):
        super().__init__(**data)
        if (self.required_citation_document_id is None) != (
            self.required_citation_document_version is None
        ):
            raise ValueError("required citation document id and version must be supplied together")


def load_rag_cases(path: Path) -> list[RagCase]:
    cases = []
    for line in path.read_text(encoding="utf-8").splitlines():
        if line.strip():
            cases.append(RagCase.model_validate(json.loads(line)))
    return cases
