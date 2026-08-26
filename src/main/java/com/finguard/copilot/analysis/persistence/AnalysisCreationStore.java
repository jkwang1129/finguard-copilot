package com.finguard.copilot.analysis.persistence;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;

import com.finguard.copilot.analysis.domain.AnalysisRun;
import com.finguard.copilot.analysis.domain.AnalysisStatus;
import com.finguard.copilot.context.FactSnapshot;
import com.finguard.copilot.context.SnapshotCodec;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.TransactionTemplate;

@Repository
public class AnalysisCreationStore {

    private static final String FIND_IDEMPOTENCY = """
            SELECT BIN_TO_UUID(k.analysis_id) AS analysis_id,
                   k.request_hash,
                   k.expires_at,
                   r.status
            FROM analysis_idempotency_key k
            JOIN analysis_run r ON r.id = k.analysis_id
            WHERE k.reviewer_username = :reviewerUsername
              AND k.idempotency_key = :idempotencyKey
            """;

    private static final String DELETE_EXPIRED_IDEMPOTENCY = """
            DELETE FROM analysis_idempotency_key
            WHERE reviewer_username = :reviewerUsername
              AND idempotency_key = :idempotencyKey
              AND analysis_id = :analysisId
              AND expires_at = :expiresAt
              AND expires_at <= CURRENT_TIMESTAMP(6)
            """;

    private static final String INSERT_IDEMPOTENCY = """
            INSERT INTO analysis_idempotency_key (
                analysis_id,
                reviewer_username,
                idempotency_key,
                request_hash,
                expires_at,
                created_at
            ) VALUES (
                :analysisId,
                :reviewerUsername,
                :idempotencyKey,
                :requestHash,
                :expiresAt,
                :createdAt
            )
            """;

    private static final String INSERT_SNAPSHOT = """
            INSERT INTO analysis_input_snapshot (
                analysis_id,
                schema_version,
                facts_json,
                facts_sha256,
                captured_at,
                expires_at,
                deleted_at
            ) VALUES (
                :analysisId,
                :schemaVersion,
                CAST(:factsJson AS JSON),
                :factsSha256,
                :capturedAt,
                :expiresAt,
                NULL
            )
            """;

    private static final String INSERT_OUTBOX = """
            INSERT INTO outbox_event (
                id,
                analysis_id,
                event_type,
                schema_version,
                payload_json,
                attempt_count,
                next_attempt_at,
                published_at,
                last_error_code,
                created_at
            ) VALUES (
                :id,
                :analysisId,
                'ANALYSIS_REQUESTED',
                :schemaVersion,
                CAST(:payloadJson AS JSON),
                0,
                :nextAttemptAt,
                NULL,
                NULL,
                :createdAt
            )
            """;

    private final NamedParameterJdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final AnalysisRunRepository runRepository;
    private final SnapshotCodec snapshotCodec;

    public AnalysisCreationStore(
            NamedParameterJdbcTemplate jdbc,
            TransactionTemplate transactions,
            AnalysisRunRepository runRepository,
            SnapshotCodec snapshotCodec
    ) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.runRepository = runRepository;
        this.snapshotCodec = snapshotCodec;
    }

    public CreatedAnalysis create(
            NewAnalysis analysis,
            FactSnapshot snapshot,
            OutboxRecord event,
            Instant idempotencyExpiresAt
    ) {
        Instant normalizedIdempotencyExpiry = validateCreation(
                analysis,
                snapshot,
                event,
                idempotencyExpiresAt
        );
        byte[] canonicalFacts = snapshotCodec.canonicalBytes(snapshot);
        AnalysisRun run = new AnalysisRun(
                analysis.analysisId(),
                AnalysisStatus.CONTEXT_READY,
                analysis.reviewerUsername(),
                analysis.reviewTaskId(),
                analysis.reviewTaskVersion(),
                analysis.idempotencyKey(),
                analysis.requestHash(),
                null,
                true,
                analysis.createdAt(),
                analysis.createdAt()
        );

        try {
            transactions.executeWithoutResult(ignored -> {
                expectOne(runRepository.insert(run));
                try {
                    expectOne(insertIdempotency(analysis, normalizedIdempotencyExpiry));
                } catch (DuplicateKeyException exception) {
                    throw new ActiveReservationConflictException();
                }
                expectOne(insertSnapshot(analysis.analysisId(), snapshot, canonicalFacts));
                expectOne(insertOutbox(event));
            });
        } catch (ActiveReservationConflictException exception) {
            throw AnalysisCreationException.conflict();
        } catch (DataAccessException | TransactionException | UnexpectedRowCountException exception) {
            throw AnalysisCreationException.storageFailure();
        }

        return new CreatedAnalysis(analysis.analysisId(), AnalysisStatus.CONTEXT_READY);
    }

    public Optional<IdempotencyReservation> findReservation(
            String reviewerUsername,
            String idempotencyKey
    ) {
        requireText(reviewerUsername, 100, "reviewerUsername");
        requireText(idempotencyKey, 100, "idempotencyKey");
        MapSqlParameterSource parameters = new MapSqlParameterSource()
                .addValue("reviewerUsername", reviewerUsername)
                .addValue("idempotencyKey", idempotencyKey);
        return jdbc.query(FIND_IDEMPOTENCY, parameters, resultSet -> {
            if (!resultSet.next()) {
                return Optional.empty();
            }
            return Optional.of(new IdempotencyReservation(
                    UUID.fromString(resultSet.getString("analysis_id")),
                    resultSet.getBytes("request_hash"),
                    resultSet.getTimestamp("expires_at").toInstant(),
                    AnalysisStatus.valueOf(resultSet.getString("status"))
            ));
        });
    }

    public boolean deleteExpiredReservation(
            String reviewerUsername,
            String idempotencyKey,
            UUID analysisId,
            Instant expiresAt
    ) {
        requireText(reviewerUsername, 100, "reviewerUsername");
        requireText(idempotencyKey, 100, "idempotencyKey");
        if (analysisId == null || expiresAt == null) {
            throw new IllegalArgumentException("expired reservation inputs are invalid");
        }
        MapSqlParameterSource parameters = new MapSqlParameterSource()
                .addValue("reviewerUsername", reviewerUsername)
                .addValue("idempotencyKey", idempotencyKey)
                .addValue("analysisId", JdbcAnalysisRunRepository.uuidBytes(analysisId))
                .addValue("expiresAt", Timestamp.from(SnapshotCodec.toMicrosecondPrecision(expiresAt)));
        return jdbc.update(DELETE_EXPIRED_IDEMPOTENCY, parameters) == 1;
    }

    private int insertIdempotency(NewAnalysis analysis, Instant expiresAt) {
        MapSqlParameterSource parameters = new MapSqlParameterSource()
                .addValue("analysisId", JdbcAnalysisRunRepository.uuidBytes(analysis.analysisId()))
                .addValue("reviewerUsername", analysis.reviewerUsername())
                .addValue("idempotencyKey", analysis.idempotencyKey())
                .addValue("requestHash", analysis.requestHash())
                .addValue("expiresAt", Timestamp.from(expiresAt))
                .addValue("createdAt", Timestamp.from(analysis.createdAt()));
        return jdbc.update(INSERT_IDEMPOTENCY, parameters);
    }

    private int insertSnapshot(UUID analysisId, FactSnapshot snapshot, byte[] canonicalFacts) {
        MapSqlParameterSource parameters = new MapSqlParameterSource()
                .addValue("analysisId", JdbcAnalysisRunRepository.uuidBytes(analysisId))
                .addValue("schemaVersion", snapshot.schemaVersion())
                .addValue("factsJson", new String(canonicalFacts, StandardCharsets.UTF_8))
                .addValue("factsSha256", snapshot.sha256())
                .addValue("capturedAt", Timestamp.from(snapshot.capturedAt()))
                .addValue("expiresAt", Timestamp.from(snapshot.expiresAt()));
        return jdbc.update(INSERT_SNAPSHOT, parameters);
    }

    private int insertOutbox(OutboxRecord event) {
        MapSqlParameterSource parameters = new MapSqlParameterSource()
                .addValue("id", JdbcAnalysisRunRepository.uuidBytes(event.eventId()))
                .addValue("analysisId", JdbcAnalysisRunRepository.uuidBytes(event.analysisId()))
                .addValue("schemaVersion", event.schemaVersion())
                .addValue("payloadJson", analysisRequestedPayload(event))
                .addValue("nextAttemptAt", Timestamp.from(event.nextAttemptAt()))
                .addValue("createdAt", Timestamp.from(event.occurredAt()));
        return jdbc.update(INSERT_OUTBOX, parameters);
    }

    private static String analysisRequestedPayload(OutboxRecord event) {
        return """
                {"schemaVersion":%d,"eventId":"%s","analysisId":"%s","occurredAt":"%s"}
                """.formatted(
                event.schemaVersion(),
                event.eventId(),
                event.analysisId(),
                event.occurredAt()
        ).strip();
    }

    private static Instant validateCreation(
            NewAnalysis analysis,
            FactSnapshot snapshot,
            OutboxRecord event,
            Instant idempotencyExpiresAt
    ) {
        if (analysis == null || snapshot == null || event == null || idempotencyExpiresAt == null) {
            throw new IllegalArgumentException("creation inputs must not be null");
        }
        Instant normalizedIdempotencyExpiry = SnapshotCodec.toMicrosecondPrecision(
                idempotencyExpiresAt);
        if (!event.analysisId().equals(analysis.analysisId())) {
            throw new IllegalArgumentException("event analysisId must match analysis");
        }
        if (snapshot.facts().reviewTask().id() != analysis.reviewTaskId()
                || snapshot.facts().reviewTask().version() != analysis.reviewTaskVersion()) {
            throw new IllegalArgumentException("snapshot review task must match analysis");
        }
        if (!normalizedIdempotencyExpiry.isAfter(analysis.createdAt())) {
            throw new IllegalArgumentException("idempotency expiry must be after creation");
        }
        return normalizedIdempotencyExpiry;
    }

    private static void expectOne(int updatedRows) {
        if (updatedRows != 1) {
            throw new UnexpectedRowCountException();
        }
    }

    public record NewAnalysis(
            UUID analysisId,
            String reviewerUsername,
            long reviewTaskId,
            long reviewTaskVersion,
            String idempotencyKey,
            byte[] requestHash,
            Instant createdAt
    ) {

        public NewAnalysis {
            if (analysisId == null) {
                throw new IllegalArgumentException("analysisId must not be null");
            }
            requireText(reviewerUsername, 100, "reviewerUsername");
            if (reviewTaskId < 1) {
                throw new IllegalArgumentException("reviewTaskId must be positive");
            }
            if (reviewTaskVersion < 0) {
                throw new IllegalArgumentException("reviewTaskVersion must not be negative");
            }
            requireText(idempotencyKey, 100, "idempotencyKey");
            if (requestHash == null || requestHash.length != AnalysisRun.SHA_256_LENGTH) {
                throw new IllegalArgumentException("requestHash must contain 32 bytes");
            }
            requestHash = requestHash.clone();
            if (createdAt == null) {
                throw new IllegalArgumentException("createdAt must not be null");
            }
            createdAt = SnapshotCodec.toMicrosecondPrecision(createdAt);
        }

        @Override
        public byte[] requestHash() {
            return requestHash.clone();
        }

        @Override
        public boolean equals(Object candidate) {
            return candidate == this
                    || candidate instanceof NewAnalysis other
                    && reviewTaskId == other.reviewTaskId
                    && reviewTaskVersion == other.reviewTaskVersion
                    && analysisId.equals(other.analysisId)
                    && reviewerUsername.equals(other.reviewerUsername)
                    && idempotencyKey.equals(other.idempotencyKey)
                    && Arrays.equals(requestHash, other.requestHash)
                    && createdAt.equals(other.createdAt);
        }

        @Override
        public int hashCode() {
            return 31 * Objects.hash(
                    analysisId,
                    reviewerUsername,
                    reviewTaskId,
                    reviewTaskVersion,
                    idempotencyKey,
                    createdAt
            ) + Arrays.hashCode(requestHash);
        }
    }

    public record OutboxRecord(
            UUID eventId,
            UUID analysisId,
            int schemaVersion,
            Instant occurredAt,
            Instant nextAttemptAt
    ) {

        public OutboxRecord {
            if (eventId == null || analysisId == null) {
                throw new IllegalArgumentException("outbox identifiers must not be null");
            }
            if (schemaVersion != 1) {
                throw new IllegalArgumentException("unsupported outbox schema version");
            }
            if (occurredAt == null || nextAttemptAt == null) {
                throw new IllegalArgumentException("outbox timestamps are invalid");
            }
            occurredAt = SnapshotCodec.toMicrosecondPrecision(occurredAt);
            nextAttemptAt = SnapshotCodec.toMicrosecondPrecision(nextAttemptAt);
            if (nextAttemptAt.isBefore(occurredAt)) {
                throw new IllegalArgumentException("outbox timestamps are invalid");
            }
        }
    }

    public record CreatedAnalysis(UUID analysisId, AnalysisStatus status) {

        public CreatedAnalysis {
            if (analysisId == null || status == null) {
                throw new IllegalArgumentException("created analysis fields must not be null");
            }
        }
    }

    public record IdempotencyReservation(
            UUID analysisId,
            byte[] requestHash,
            Instant expiresAt,
            AnalysisStatus status
    ) {

        public IdempotencyReservation {
            if (analysisId == null || expiresAt == null || status == null) {
                throw new IllegalArgumentException("reservation fields must not be null");
            }
            if (requestHash == null || requestHash.length != AnalysisRun.SHA_256_LENGTH) {
                throw new IllegalArgumentException("reservation hash must contain 32 bytes");
            }
            requestHash = requestHash.clone();
            expiresAt = SnapshotCodec.toMicrosecondPrecision(expiresAt);
        }

        public IdempotencyReservation(UUID analysisId, byte[] requestHash, Instant expiresAt) {
            this(analysisId, requestHash, expiresAt, AnalysisStatus.CONTEXT_READY);
        }

        @Override
        public byte[] requestHash() {
            return requestHash.clone();
        }
    }

    public static final class AnalysisCreationException extends RuntimeException {

        public enum Kind {
            CONFLICT,
            STORAGE_FAILURE
        }

        private final Kind kind;

        private AnalysisCreationException(Kind kind, String message) {
            super(message);
            this.kind = kind;
        }

        public Kind kind() {
            return kind;
        }

        public static AnalysisCreationException conflict() {
            return new AnalysisCreationException(
                    Kind.CONFLICT,
                    "An active idempotency reservation already exists"
            );
        }

        private static AnalysisCreationException storageFailure() {
            return new AnalysisCreationException(
                    Kind.STORAGE_FAILURE,
                    "Analysis creation could not be persisted"
            );
        }
    }

    private static final class UnexpectedRowCountException extends RuntimeException {
    }

    private static final class ActiveReservationConflictException extends RuntimeException {
    }

    private static void requireText(String value, int maxLength, String name) {
        if (value == null || value.isBlank() || value.length() > maxLength) {
            throw new IllegalArgumentException(name + " must contain 1 to " + maxLength + " characters");
        }
    }
}
