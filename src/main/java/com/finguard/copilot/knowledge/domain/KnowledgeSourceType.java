package com.finguard.copilot.knowledge.domain;

public enum KnowledgeSourceType {
    POLICY,
    RULE_EXPLANATION,
    MANUAL,
    RUNBOOK,
    SANITIZED_CASE,
    DYNAMIC_CORE_FACT;

    public boolean admissible() {
        return this != DYNAMIC_CORE_FACT;
    }
}
