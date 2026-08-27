package com.finguard.copilot.knowledge.domain;

import java.time.Instant;

public record KnowledgeIndexVersion(
        String indexVersion,
        KnowledgeIndexStatus status,
        Instant createdAt,
        Instant publishedAt,
        String replacedVersion
) {

    public KnowledgeIndexVersion {
        requireText(indexVersion, "indexVersion");
        if (status == null) {
            throw new IllegalArgumentException("status must not be null");
        }
        if (createdAt == null) {
            throw new IllegalArgumentException("createdAt must not be null");
        }
        if (replacedVersion != null && replacedVersion.isBlank()) {
            throw new IllegalArgumentException("replacedVersion must not be blank");
        }
        if (status == KnowledgeIndexStatus.PUBLISHED && publishedAt == null) {
            throw new IllegalArgumentException("published indexes require publishedAt");
        }
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
