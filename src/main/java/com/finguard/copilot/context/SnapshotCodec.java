package com.finguard.copilot.context;

import java.io.IOException;
import java.math.BigDecimal;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.finguard.copilot.coreclient.port.ReviewTaskContext;
import com.finguard.copilot.coreclient.port.ReviewTaskContext.RiskFact;
import org.springframework.stereotype.Component;

@Component
public final class SnapshotCodec {

    public static final Duration DEFAULT_SNAPSHOT_RETENTION = Duration.ofDays(30);

    private static final Comparator<RiskFact> RISK_FACT_ORDER = Comparator
            .comparing(RiskFact::code)
            .thenComparing(RiskFact::value)
            .thenComparing(RiskFact::source);

    private static final ObjectMapper CANONICAL_MAPPER = JsonMapper.builder()
            .addModule(new JavaTimeModule())
            .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();

    public FactSnapshot capture(ReviewTaskContext facts) {
        return capture(facts, DEFAULT_SNAPSHOT_RETENTION);
    }

    public FactSnapshot capture(ReviewTaskContext facts, Duration retention) {
        if (facts == null) {
            throw new IllegalArgumentException("facts must not be null");
        }
        if (retention == null || retention.isZero() || retention.isNegative()) {
            throw new IllegalArgumentException("snapshot retention must be positive");
        }

        ReviewTaskContext normalizedFacts = normalize(facts);
        byte[] canonicalBytes = serialize(normalizedFacts);
        Instant expiresAt;
        try {
            expiresAt = toMicrosecondPrecision(normalizedFacts.capturedAt().plus(retention));
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException("snapshot retention is out of range", exception);
        }
        return new FactSnapshot(
                FactSnapshot.CURRENT_SCHEMA_VERSION,
                normalizedFacts,
                normalizedFacts.capturedAt(),
                expiresAt,
                sha256(canonicalBytes)
        );
    }

    public byte[] canonicalBytes(FactSnapshot snapshot) {
        if (snapshot == null) {
            throw new IllegalArgumentException("snapshot must not be null");
        }

        byte[] canonicalBytes = serialize(normalize(snapshot.facts()));
        if (!MessageDigest.isEqual(snapshot.sha256(), sha256(canonicalBytes))) {
            throw new IllegalArgumentException("snapshot checksum does not match facts");
        }
        return canonicalBytes;
    }

    public ReviewTaskContext decode(byte[] canonicalBytes) {
        if (canonicalBytes == null || canonicalBytes.length == 0) {
            throw new IllegalArgumentException("canonicalBytes must not be empty");
        }
        try {
            ReviewTaskContext decoded = CANONICAL_MAPPER.readValue(
                    canonicalBytes.clone(), ReviewTaskContext.class);
            return normalize(decoded);
        } catch (IOException | IllegalArgumentException exception) {
            throw new IllegalArgumentException("snapshot JSON is invalid");
        }
    }

    public ReviewTaskContext decodeAndVerify(byte[] storedJson, byte[] storedSha256) {
        if (storedSha256 == null || storedSha256.length != FactSnapshot.SHA_256_LENGTH) {
            throw new IllegalArgumentException("snapshot checksum verification failed");
        }
        ReviewTaskContext decoded = decode(storedJson);
        byte[] recalculated = sha256(serialize(decoded));
        if (!MessageDigest.isEqual(recalculated, storedSha256.clone())) {
            throw new IllegalArgumentException("snapshot checksum verification failed");
        }
        return decoded;
    }

    public static Instant toMicrosecondPrecision(Instant value) {
        if (value == null) {
            throw new IllegalArgumentException("timestamp must not be null");
        }
        return value.truncatedTo(ChronoUnit.MICROS);
    }

    static ReviewTaskContext normalize(ReviewTaskContext facts) {
        List<RiskFact> sortedRiskFacts = facts.riskFacts().stream()
                .sorted(RISK_FACT_ORDER)
                .toList();
        ReviewTaskContext.ReviewTask reviewTask = facts.reviewTask();
        ReviewTaskContext.ReviewTask normalizedReviewTask = new ReviewTaskContext.ReviewTask(
                reviewTask.id(),
                reviewTask.version(),
                reviewTask.status(),
                reviewTask.ruleCode(),
                reviewTask.reasonCode(),
                toMicrosecondPrecision(reviewTask.createdAt())
        );
        ReviewTaskContext.Transaction transaction = facts.transaction();
        ReviewTaskContext.Transaction normalizedTransaction = new ReviewTaskContext.Transaction(
                transaction.id(),
                transaction.accountId(),
                canonicalDecimal(transaction.amount()),
                transaction.currency(),
                toMicrosecondPrecision(transaction.occurredAt()),
                transaction.sourceType()
        );
        return new ReviewTaskContext(
                normalizedReviewTask,
                normalizedTransaction,
                facts.reconciliation(),
                sortedRiskFacts,
                facts.accountSummary(),
                toMicrosecondPrecision(facts.capturedAt())
        );
    }

    private static BigDecimal canonicalDecimal(BigDecimal value) {
        return new BigDecimal(value.stripTrailingZeros().toPlainString());
    }

    private static byte[] serialize(ReviewTaskContext facts) {
        try {
            return CANONICAL_MAPPER.writeValueAsBytes(facts);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("snapshot JSON cannot be encoded");
        }
    }

    private static byte[] sha256(byte[] bytes) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(Arrays.copyOf(bytes, bytes.length));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable");
        }
    }
}
