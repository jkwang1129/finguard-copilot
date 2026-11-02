"""Pure comparisons for synthetic offline cases; this is not a policy validator."""

from dataclasses import dataclass
from decimal import Decimal, InvalidOperation
import re

_NUMBER = re.compile(r"(?:0|[1-9][0-9]*)(?:\.[0-9]+)?\Z")
_ID = re.compile(r"[A-Za-z0-9][A-Za-z0-9._:-]{0,127}\Z")


@dataclass(frozen=True)
class Rule:
    rule_code: str
    currency: str
    operator: str
    threshold: Decimal
    document_id: str

    def __post_init__(self):
        if (not isinstance(self.rule_code, str) or not _ID.fullmatch(self.rule_code)
                or not isinstance(self.document_id, str) or not _ID.fullmatch(self.document_id)
                or not isinstance(self.currency, str) or not re.fullmatch(r"[A-Z]{3}", self.currency)
                or not isinstance(self.operator, str) or self.operator not in {"GT", "GTE", "LT", "LTE"}
                or not isinstance(self.threshold, Decimal) or not self.threshold.is_finite()
                or self.threshold < 0):
            raise ValueError("invalid synthetic threshold rule")


def evaluate(rule: Rule, rule_code: str, currency: str, amount: str | None,
             citation_ids: list[str]) -> str | None:
    """Return a hypothetical direction, or None when required inputs are unverified.

    The document ID is only an input-presence check. This helper cannot validate
    policy text, versions, checksums, provenance or applicability and must not be
    used as a production decision or represented as independently verified.
    """
    if (not isinstance(rule, Rule) or rule_code != rule.rule_code or currency != rule.currency
            or not isinstance(amount, str) or not _NUMBER.fullmatch(amount)
            or not isinstance(citation_ids, list) or rule.document_id not in citation_ids):
        return None
    try:
        value = Decimal(amount)
    except InvalidOperation:
        return None
    ordering = value.compare(rule.threshold)
    triggered = {"GT": ordering > 0, "GTE": ordering >= 0,
                 "LT": ordering < 0, "LTE": ordering <= 0}[rule.operator]
    return "CONFIRMED" if triggered else "IGNORED"
