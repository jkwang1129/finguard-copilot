package com.finguard.copilot.knowledge.qdrant;

import java.net.URI;
import java.time.Duration;

public record QdrantConfiguration(
        URI endpoint,
        String collectionPrefix,
        Duration connectTimeout,
        Duration readTimeout,
        int dimension
) {

    public QdrantConfiguration {
        if (endpoint == null) {
            throw new IllegalArgumentException("endpoint must not be null");
        }
        if (collectionPrefix == null || collectionPrefix.isBlank()) {
            throw new IllegalArgumentException("collectionPrefix must not be blank");
        }
        if (connectTimeout == null || connectTimeout.isNegative() || connectTimeout.isZero()) {
            throw new IllegalArgumentException("connectTimeout must be positive");
        }
        if (readTimeout == null || readTimeout.isNegative() || readTimeout.isZero()) {
            throw new IllegalArgumentException("readTimeout must be positive");
        }
        if (dimension < 1) {
            throw new IllegalArgumentException("dimension must be positive");
        }
    }
}
