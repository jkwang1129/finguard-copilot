package com.finguard.copilot.analysis.application;

/** Bounded diagnostic metadata; never contains model output or validation reason text. */
public enum ModelValidationStage {
    UNKNOWN,
    OUTPUT_SCHEMA,
    CITATION,
    UNTRUSTED_INSTRUCTION,
    CORE_FACTS,
    CORE_POLICY,
    REVIEWED_POLICY,
    FINAL_FACTS
}
