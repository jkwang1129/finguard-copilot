package com.finguard.copilot.knowledge.port;

import java.util.Map;

public record VectorDocument(
        String id,
        String indexVersion,
        String content,
        EmbeddingVector embedding,
        Map<String, String> metadata
) {

    public VectorDocument {
        requireText(id, "id");
        requireText(indexVersion, "indexVersion");
        if (content == null) {
            throw new IllegalArgumentException("content must not be null");
        }
        if (embedding == null) {
            throw new IllegalArgumentException("embedding must not be null");
        }
        if (metadata == null || metadata.entrySet().stream()
                .anyMatch(entry -> isBlank(entry.getKey()) || isBlank(entry.getValue()))) {
            throw new IllegalArgumentException("metadata must not contain null or blank entries");
        }
        metadata = Map.copyOf(metadata);
    }

    private static void requireText(String value, String name) {
        if (isBlank(value)) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
