package com.finguard.copilot.knowledge.port;

public interface EmbeddingGateway {

    EmbeddingVector embed(String canonicalText, String embeddingVersion);

    default EmbeddingVector embedQuery(String text, String embeddingVersion) {
        return embed(text, embeddingVersion);
    }

    default EmbeddingVector embedDocument(String text, String embeddingVersion) {
        return embed(text, embeddingVersion);
    }

    default java.util.Map<String,Object> descriptor() {
        return java.util.Map.of("implementation", getClass().getSimpleName());
    }

    default EmbeddingVector embed(String canonicalText) {
        return embed(canonicalText, "legacy");
    }
}
