from __future__ import annotations

from collections.abc import Iterable, Sequence

from .rag_cases import RagCase


def recall_at_k(expected_chunk_ids: Iterable[str], retrieved_chunk_ids: Sequence[str]) -> float:
    expected = set(expected_chunk_ids)
    if not expected:
        return 1.0 if not retrieved_chunk_ids else 0.0
    return len(expected.intersection(retrieved_chunk_ids)) / len(expected)


def citation_precision(case: RagCase, retrieved_chunk_ids: Sequence[str], retrieved_documents: dict[str, tuple[str, str]]) -> float:
    if not retrieved_chunk_ids:
        return 1.0 if case.required_citation_document_id is None else 0.0
    if case.required_citation_document_id is None:
        return 1.0
    required = (case.required_citation_document_id, case.required_citation_document_version)
    return sum(retrieved_documents.get(chunk_id) == required for chunk_id in retrieved_chunk_ids) / len(retrieved_chunk_ids)


def no_answer_correctness(case: RagCase, retrieved_chunk_ids: Sequence[str]) -> float:
    return 1.0 if not case.expected_chunk_ids and not retrieved_chunk_ids else 0.0
