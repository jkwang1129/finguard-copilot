from pathlib import Path

from finguard_agent_evaluation.rag_cases import RagCase, load_rag_cases
from finguard_agent_evaluation.rag_metrics import citation_precision, no_answer_correctness, recall_at_k


def test_metrics_are_deterministic_for_answerable_case():
    case = RagCase(
        case_id="case-1",
        query="risk",
        expected_chunk_ids=("chunk-a", "chunk-b"),
        required_citation_document_id="doc-1",
        required_citation_document_version="v1",
    )
    retrieved = ["chunk-b", "chunk-x"]
    documents = {"chunk-b": ("doc-1", "v1"), "chunk-x": ("doc-2", "v1")}

    assert recall_at_k(case.expected_chunk_ids, retrieved) == 0.5
    assert citation_precision(case, retrieved, documents) == 0.5
    assert no_answer_correctness(case, retrieved) == 0.0


def test_no_answer_requires_empty_retrieval():
    case = RagCase(case_id="case-2", query="unknown")
    assert no_answer_correctness(case, []) == 1.0
    assert no_answer_correctness(case, ["chunk-a"]) == 0.0


def test_fixed_rag_dataset_is_sanitized_and_typed():
    cases = load_rag_cases(Path(__file__).parents[1] / "data" / "rag_cases.jsonl")

    assert len(cases) == 3
    assert {case.case_id for case in cases} == {
        "policy-high-amount-001",
        "manual-audit-001",
        "unknown-policy-001",
    }
    assert all("transactionId" not in case.query for case in cases)
