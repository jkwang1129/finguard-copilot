package com.finguard.copilot.knowledge.domain;

import java.time.Instant;

public record KnowledgeDocument(
        String documentId,
        KnowledgeSourceType sourceType,
        String title,
        String canonicalChecksum,
        boolean sanitized,
        String sourceVersion,
        Instant registeredAt,
        String status
) {

    public static final String ACTIVE_STATUS = "ACTIVE";

    public KnowledgeDocument {
        requireText(documentId, "documentId");
        if (sourceType == null || !sourceType.admissible()) {
            throw new IllegalArgumentException("sourceType is not admissible");
        }
        requireText(title, "title");
        requireText(canonicalChecksum, "canonicalChecksum");
        requireText(sourceVersion, "sourceVersion");
        if (registeredAt == null) {
            throw new IllegalArgumentException("registeredAt must not be null");
        }
        if (!sanitized) {
            throw new IllegalArgumentException("knowledge documents must be sanitized");
        }
        requireText(status, "status");
    }

    public static KnowledgeDocument register(
            String documentId,
            KnowledgeSourceType sourceType,
            String title,
            String sourceVersion,
            String canonicalChecksum,
            Instant registeredAt
    ) {
        return new KnowledgeDocument(
                documentId,
                sourceType,
                title,
                canonicalChecksum,
                true,
                sourceVersion,
                registeredAt,
                ACTIVE_STATUS);
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
