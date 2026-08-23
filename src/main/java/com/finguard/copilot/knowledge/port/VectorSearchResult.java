package com.finguard.copilot.knowledge.port;

public record VectorSearchResult(VectorDocument document, double score) {

    public VectorSearchResult {
        if (document == null) {
            throw new IllegalArgumentException("document must not be null");
        }
        if (!Double.isFinite(score) || score < -1.0 || score > 1.0) {
            throw new IllegalArgumentException("score must be finite and between -1 and 1");
        }
    }
}
