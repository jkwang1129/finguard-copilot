package com.finguard.copilot.dispatch;

import java.time.Instant;
import java.util.UUID;

public record AnalysisRequestedMessage(
        int schemaVersion,
        UUID eventId,
        UUID analysisId,
        Instant occurredAt
) {

    public static final int CURRENT_SCHEMA_VERSION = 1;

    public AnalysisRequestedMessage {
        if (schemaVersion != CURRENT_SCHEMA_VERSION) {
            throw new IllegalArgumentException("unsupported analysis message schema version");
        }
        if (eventId == null || analysisId == null || occurredAt == null) {
            throw new IllegalArgumentException("analysis message fields must not be null");
        }
    }
}
