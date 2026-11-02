"""Pure offline LangGraph reference for the synthetic single-rule contract."""

import re
from decimal import Decimal
from typing import TypedDict

from langgraph.graph import END, START, StateGraph

_ID = re.compile(r"[A-Za-z0-9][A-Za-z0-9._:-]{0,127}\Z")
_AMOUNT = re.compile(r"(?:0|[1-9][0-9]*)(?:\.[0-9]+)?\Z")
_KEYS = {"case_id", "category", "rule_code", "currency", "amount", "operator",
         "threshold", "policy_document_id", "citation_ids", "expected"}
_LABELS = {"CONFIRMED", "IGNORED", "INSUFFICIENT_EVIDENCE"}


class State(TypedDict):
    case: dict
    recommendation: str


def _decide(state: State) -> dict:
    case = state["case"]
    authorized = (case["rule_code"] == "SYNTH_LIMIT" and case["currency"] == "USD"
                  and case["operator"] == "GTE" and case["threshold"] == "100.00"
                  and case["policy_document_id"] == "SYNTH_POLICY_V1"
                  and "SYNTH_POLICY_V1" in case["citation_ids"])
    value = case["amount"]
    if not authorized or not isinstance(value, str) or not _AMOUNT.fullmatch(value):
        return {"recommendation": "INSUFFICIENT_EVIDENCE"}
    return {"recommendation": "CONFIRMED" if Decimal(value) >= Decimal("100.00") else "IGNORED"}


_builder = StateGraph(State)
_builder.add_node("decide", _decide)
_builder.add_edge(START, "decide")
_builder.add_edge("decide", END)
_graph = _builder.compile()


def run_case(case: dict) -> dict:
    if (not isinstance(case, dict) or set(case) != _KEYS
            or any(not isinstance(case[key], str) or not _ID.fullmatch(case[key])
                   for key in ("case_id", "category", "rule_code", "currency", "operator",
                               "threshold", "policy_document_id", "expected"))
            or case["expected"] not in _LABELS
            or not isinstance(case["citation_ids"], list)
            or any(not isinstance(item, str) or not _ID.fullmatch(item)
                   for item in case["citation_ids"])
            or (case["amount"] is not None and
                (not isinstance(case["amount"], str) or len(case["amount"]) > 128))):
        raise ValueError("invalid synthetic reference case")
    state = _graph.invoke({"case": {key: value for key, value in case.items() if key != "expected"}})
    return {"case_id": case["case_id"],
            "result": {"status": "completed", "recommendation": state["recommendation"]}}
