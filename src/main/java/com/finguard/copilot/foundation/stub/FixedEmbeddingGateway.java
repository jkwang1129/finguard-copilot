package com.finguard.copilot.foundation.stub;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;

import com.finguard.copilot.knowledge.port.EmbeddingGateway;
import com.finguard.copilot.knowledge.port.EmbeddingVector;

public final class FixedEmbeddingGateway implements EmbeddingGateway {

    private static final int MIN_DIMENSIONS = 4;
    private static final int MAX_DIMENSIONS = 4096;

    private final int dimensions;

    public FixedEmbeddingGateway(int dimensions) {
        if (dimensions < MIN_DIMENSIONS || dimensions > MAX_DIMENSIONS) {
            throw new IllegalArgumentException("dimensions must be between 4 and 4096");
        }
        this.dimensions = dimensions;
    }

    @Override
    public EmbeddingVector embed(String text) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("text must not be blank");
        }

        byte[] digest = sha256(text);
        double[] raw = new double[dimensions];
        double squaredNorm = 0.0;
        for (int index = 0; index < dimensions; index++) {
            int unsignedByte = Byte.toUnsignedInt(digest[index % digest.length]);
            raw[index] = (unsignedByte - 127.5) / 127.5;
            squaredNorm += raw[index] * raw[index];
        }

        double norm = Math.sqrt(squaredNorm);
        List<Double> normalized = new ArrayList<>(dimensions);
        for (double value : raw) {
            normalized.add(value / norm);
        }
        return new EmbeddingVector(normalized);
    }

    private byte[] sha256(String text) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
