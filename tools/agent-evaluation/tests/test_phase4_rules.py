from decimal import Decimal

import pytest

from finguard_agent_evaluation.phase4_rules import Rule, evaluate


@pytest.mark.parametrize(("amount", "expected"), [
    ("0.100000000000000001", "CONFIRMED"),
    ("0.100000000000000000", "IGNORED"),
    ("0.099999999999999999", "IGNORED"),
])
def test_exact_decimal_threshold(amount, expected):
    rule = Rule("RULE", "USD", "GT", Decimal("0.100000000000000000"), "policy")
    assert evaluate(rule, "RULE", "USD", amount, ["policy"]) == expected


def test_unknown_rule_missing_facts_or_missing_citation_are_unverified():
    rule = Rule("RULE", "USD", "GTE", Decimal("10"), "policy")
    assert evaluate(rule, "OTHER", "USD", "10", ["policy"]) is None
    assert evaluate(rule, "RULE", "EUR", "10", ["policy"]) is None
    assert evaluate(rule, "RULE", "USD", None, ["policy"]) is None
    assert evaluate(rule, "RULE", "USD", "10", []) is None


@pytest.mark.parametrize("amount", ["NaN", "Infinity", "1e2", "-1", "1,000", " 10"])
def test_invalid_amount_is_unverified(amount):
    rule = Rule("RULE", "USD", "GT", Decimal("10"), "policy")
    assert evaluate(rule, "RULE", "USD", amount, ["policy"]) is None


def test_invalid_rule_cannot_be_registered():
    with pytest.raises(ValueError):
        Rule("RULE", "USD", "GTE", Decimal("NaN"), "policy")


@pytest.mark.parametrize(("operator", "equal_expected"), [
    ("GT", "IGNORED"), ("GTE", "CONFIRMED"),
    ("LT", "IGNORED"), ("LTE", "CONFIRMED"),
])
def test_operator_boundaries(operator, equal_expected):
    rule = Rule("RULE", "USD", operator, Decimal("10"), "policy")
    assert evaluate(rule, "RULE", "USD", "10.00", ["policy"]) == equal_expected
