package com.finguard.copilot.dispatch;

import java.time.Instant;
import java.util.UUID;

public record AnalysisRequestedMessage(
        int schemaVersion,
        UUID eventId,
        UUID analysisId,
        Instant occurredAt,
        @com.fasterxml.jackson.databind.annotation.JsonDeserialize(using=com.finguard.copilot.observability.TraceparentDeserializer.class)
        String traceparent
) {

    public static final int CURRENT_SCHEMA_VERSION = 1;

    public AnalysisRequestedMessage(int schemaVersion,UUID eventId,UUID analysisId,Instant occurredAt) {
        this(schemaVersion,eventId,analysisId,occurredAt,null);
    }
    public AnalysisRequestedMessage withTraceparent(String value) {
        return new AnalysisRequestedMessage(schemaVersion,eventId,analysisId,occurredAt,value);
    }

    public AnalysisRequestedMessage {
        traceparent=com.finguard.copilot.observability.AnalysisTelemetry.validTraceparent(traceparent);
        if (schemaVersion != CURRENT_SCHEMA_VERSION) {
            throw new IllegalArgumentException("unsupported analysis message schema version");
        }
        if (eventId == null || analysisId == null || occurredAt == null) {
            throw new IllegalArgumentException("analysis message fields must not be null");
        }
    }
}
