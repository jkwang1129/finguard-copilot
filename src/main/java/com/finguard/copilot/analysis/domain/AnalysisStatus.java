package com.finguard.copilot.analysis.domain;

public enum AnalysisStatus {
    CONTEXT_READY,
    QUEUED,
    RETRIEVAL_READY,
    GENERATING,
    VALIDATING,
    SUCCEEDED,
    FAILED,
    PLANNING,
    TOOL_RUNNING,
    WAITING_FOR_HUMAN
}
