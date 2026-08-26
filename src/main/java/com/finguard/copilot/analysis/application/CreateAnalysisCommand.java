package com.finguard.copilot.analysis.application;

public record CreateAnalysisCommand(long reviewTaskId, String idempotencyKey) {

    public CreateAnalysisCommand {
        if (reviewTaskId < 1) {
            throw new IllegalArgumentException("reviewTaskId must be positive");
        }
        if (idempotencyKey == null) {
            throw new IllegalArgumentException("idempotencyKey must not be null");
        }
    }
}
