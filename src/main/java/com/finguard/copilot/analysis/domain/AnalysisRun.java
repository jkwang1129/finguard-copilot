package com.finguard.copilot.analysis.domain;

import java.time.Instant;
import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;

import com.finguard.copilot.context.SnapshotCodec;

public record AnalysisRun(
        UUID id,
        AnalysisStatus status,
        String reviewerUsername,
        long reviewTaskId,
        long reviewTaskVersion,
        String idempotencyKey,
        byte[] requestHash,
        String failureCode,
        boolean fullyReproducible,
        Instant createdAt,
        Instant updatedAt
) {

    public static final int SHA_256_LENGTH = 32;

    public AnalysisRun {
        if (id == null) {
            throw new IllegalArgumentException("id must not be null");
        }
        if (status == null) {
            throw new IllegalArgumentException("status must not be null");
        }
        requireText(reviewerUsername, 100, "reviewerUsername");
        if (reviewTaskId < 1) {
            throw new IllegalArgumentException("reviewTaskId must be positive");
        }
        if (reviewTaskVersion < 0) {
            throw new IllegalArgumentException("reviewTaskVersion must not be negative");
        }
        requireText(idempotencyKey, 100, "idempotencyKey");
        if (requestHash == null || requestHash.length != SHA_256_LENGTH) {
            throw new IllegalArgumentException("requestHash must contain 32 bytes");
        }
        requestHash = requestHash.clone();
        if (failureCode != null && (failureCode.isBlank() || failureCode.length() > 64)) {
            throw new IllegalArgumentException("failureCode must be null or 1 to 64 characters");
        }
        if ((status == AnalysisStatus.FAILED) != (failureCode != null)) {
            throw new IllegalArgumentException("failureCode must be present exactly for FAILED status");
        }
        if (createdAt == null || updatedAt == null) {
            throw new IllegalArgumentException("run timestamps are invalid");
        }
        createdAt = SnapshotCodec.toMicrosecondPrecision(createdAt);
        updatedAt = SnapshotCodec.toMicrosecondPrecision(updatedAt);
        if (updatedAt.isBefore(createdAt)) {
            throw new IllegalArgumentException("run timestamps are invalid");
        }
    }

    @Override
    public byte[] requestHash() {
        return requestHash.clone();
    }

    @Override
    public boolean equals(Object candidate) {
        return candidate == this
                || candidate instanceof AnalysisRun other
                && reviewTaskId == other.reviewTaskId
                && reviewTaskVersion == other.reviewTaskVersion
                && fullyReproducible == other.fullyReproducible
                && id.equals(other.id)
                && status == other.status
                && reviewerUsername.equals(other.reviewerUsername)
                && idempotencyKey.equals(other.idempotencyKey)
                && Arrays.equals(requestHash, other.requestHash)
                && Objects.equals(failureCode, other.failureCode)
                && createdAt.equals(other.createdAt)
                && updatedAt.equals(other.updatedAt);
    }

    @Override
    public int hashCode() {
        return 31 * Objects.hash(
                id,
                status,
                reviewerUsername,
                reviewTaskId,
                reviewTaskVersion,
                idempotencyKey,
                failureCode,
                fullyReproducible,
                createdAt,
                updatedAt
        ) + Arrays.hashCode(requestHash);
    }

    private static void requireText(String value, int maxLength, String name) {
        if (value == null || value.isBlank() || value.length() > maxLength) {
            throw new IllegalArgumentException(name + " must contain 1 to " + maxLength + " characters");
        }
    }
}
