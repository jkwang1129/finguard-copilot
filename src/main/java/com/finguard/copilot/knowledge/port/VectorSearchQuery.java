package com.finguard.copilot.knowledge.port;

public record VectorSearchQuery(String indexVersion, EmbeddingVector embedding, int topK) {

    public VectorSearchQuery {
        if (indexVersion == null || indexVersion.isBlank()) {
            throw new IllegalArgumentException("indexVersion must not be blank");
        }
        if (embedding == null) {
            throw new IllegalArgumentException("embedding must not be null");
        }
        if (topK < 1) {
            throw new IllegalArgumentException("topK must be positive");
        }
    }
}
