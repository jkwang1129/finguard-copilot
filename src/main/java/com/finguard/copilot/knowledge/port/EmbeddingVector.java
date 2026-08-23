package com.finguard.copilot.knowledge.port;

import java.util.List;

public record EmbeddingVector(List<Double> values) {

    public EmbeddingVector {
        if (values == null || values.isEmpty()) {
            throw new IllegalArgumentException("values must not be empty");
        }
        if (values.stream().anyMatch(value -> value == null || !Double.isFinite(value))) {
            throw new IllegalArgumentException("values must contain only finite numbers");
        }
        values = List.copyOf(values);
    }

    public int dimension() {
        return values.size();
    }
}
