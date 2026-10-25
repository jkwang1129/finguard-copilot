package com.finguard.copilot.context;

import java.time.Instant;
import java.util.Arrays;
import java.util.Objects;

import com.finguard.copilot.coreclient.port.ReviewTaskContext;

public record FactSnapshot(
        int schemaVersion,
        ReviewTaskContext facts,
        Instant capturedAt,
        Instant expiresAt,
        byte[] sha256
) {

    public static final int CURRENT_SCHEMA_VERSION = 1;
    public static final int SHA_256_LENGTH = 32;

    public FactSnapshot {
        if (schemaVersion != CURRENT_SCHEMA_VERSION) {
            throw new IllegalArgumentException("unsupported snapshot schema version");
        }
        if (facts == null) {
            throw new IllegalArgumentException("facts must not be null");
        }
        facts = SnapshotCodec.normalize(facts);
        if (capturedAt == null) {
            throw new IllegalArgumentException("capturedAt must not be null");
        }
        capturedAt = SnapshotCodec.toMicrosecondPrecision(capturedAt);
        if (!capturedAt.equals(facts.capturedAt())) {
            throw new IllegalArgumentException("capturedAt must match facts capture time");
        }
        if (expiresAt == null) {
            throw new IllegalArgumentException("expiresAt must be after capturedAt");
        }
        expiresAt = SnapshotCodec.toMicrosecondPrecision(expiresAt);
        if (!expiresAt.isAfter(capturedAt)) {
            throw new IllegalArgumentException("expiresAt must be after capturedAt");
        }
        if (sha256 == null || sha256.length != SHA_256_LENGTH) {
            throw new IllegalArgumentException("sha256 must contain 32 bytes");
        }
        sha256 = sha256.clone();
    }

    @Override
    public byte[] sha256() {
        return sha256.clone();
    }

    @Override
    public boolean equals(Object candidate) {
        return candidate == this
                || candidate instanceof FactSnapshot other
                && schemaVersion == other.schemaVersion
                && facts.equals(other.facts)
                && capturedAt.equals(other.capturedAt)
                && expiresAt.equals(other.expiresAt)
                && Arrays.equals(sha256, other.sha256);
    }

    @Override
    public int hashCode() {
        return 31 * Objects.hash(schemaVersion, facts, capturedAt, expiresAt)
                + Arrays.hashCode(sha256);
    }
}
