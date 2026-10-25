package com.finguard.copilot.knowledge.port;

public interface EmbeddingGateway {

    EmbeddingVector embed(String canonicalText, String embeddingVersion);

    default EmbeddingVector embed(String canonicalText) {
        return embed(canonicalText, "legacy");
    }
}
