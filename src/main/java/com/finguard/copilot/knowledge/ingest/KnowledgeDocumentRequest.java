package com.finguard.copilot.knowledge.ingest;

import com.finguard.copilot.knowledge.domain.KnowledgeSourceType;

public record KnowledgeDocumentRequest(
        String documentId,
        KnowledgeSourceType sourceType,
        String title,
        String sourceVersion,
        String rawText
) {

    public KnowledgeDocumentRequest {
        requireText(documentId, "documentId");
        if (sourceType == null) {
            throw new IllegalArgumentException("sourceType must not be null");
        }
        requireText(title, "title");
        requireText(sourceVersion, "sourceVersion");
        requireText(rawText, "rawText");
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
