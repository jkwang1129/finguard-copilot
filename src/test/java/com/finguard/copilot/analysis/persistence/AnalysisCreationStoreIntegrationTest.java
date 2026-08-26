package com.finguard.copilot.analysis.persistence;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.finguard.copilot.analysis.domain.AnalysisRun;
import com.finguard.copilot.analysis.domain.AnalysisStatus;
import com.finguard.copilot.analysis.persistence.AnalysisCreationStore.AnalysisCreationException;
import com.finguard.copilot.analysis.persistence.AnalysisCreationStore.CreatedAnalysis;
import com.finguard.copilot.analysis.persistence.AnalysisCreationStore.NewAnalysis;
import com.finguard.copilot.analysis.persistence.AnalysisCreationStore.OutboxRecord;
import com.finguard.copilot.context.FactSnapshot;
import com.finguard.copilot.context.SnapshotCodec;
import com.finguard.copilot.coreclient.port.ReviewTaskContext;
import com.finguard.copilot.coreclient.port.ReviewTaskContext.AccountSummary;
import com.finguard.copilot.coreclient.port.ReviewTaskContext.Reconciliation;
import com.finguard.copilot.coreclient.port.ReviewTaskContext.ReviewTask;
import com.finguard.copilot.coreclient.port.ReviewTaskContext.RiskFact;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(properties = {
        "COPILOT_DB_URL=jdbc:mysql://unused/test",
        "COPILOT_DB_USERNAME=test-only",
        "COPILOT_DB_PASSWORD=",
        "spring.datasource.hikari.maximum-pool-size=4",
        "spring.datasource.hikari.connection-timeout=5000"
})
@Testcontainers
class AnalysisCreationStoreIntegrationTest {

    private static final Instant CAPTURED_AT = Instant.parse("2026-08-25T02:03:04.123456987Z");
    private static final Instant CREATED_AT = CAPTURED_AT.plusSeconds(1).plusNanos(321);
    private static final SnapshotCodec SNAPSHOT_CODEC = new SnapshotCodec();
    private static final ObjectMapper JSON_MAPPER = new ObjectMapper();

    @Container
    @ServiceConnection
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4.10")
            .withCommand("--log-bin-trust-function-creators=1");

    @Autowired
    private AnalysisCreationStore store;

    @Autowired
    private NamedParameterJdbcTemplate jdbc;

    @BeforeEach
    void cleanTables() {
        dropFailureTriggers();
        jdbc.getJdbcTemplate().update("DELETE FROM analysis_message_receipt");
        jdbc.getJdbcTemplate().update("DELETE FROM analysis_result");
        jdbc.getJdbcTemplate().update("DELETE FROM outbox_event");
        jdbc.getJdbcTemplate().update("DELETE FROM analysis_input_snapshot");
        jdbc.getJdbcTemplate().update("DELETE FROM analysis_idempotency_key");
        jdbc.getJdbcTemplate().update("DELETE FROM analysis_run");
    }

    @AfterEach
    void removeFailureTriggers() {
        dropFailureTriggers();
    }

    @Test
    void createAtomicallyWritesOneRunReservationSnapshotAndRequestedEvent() throws Exception {
        CreationFixture fixture = fixture("reviewer-a", "request-key-a");

        CreatedAnalysis created = store.create(
                fixture.analysis(),
                fixture.snapshot(),
                fixture.event(),
                fixture.idempotencyExpiresAt()
        );

        assertThat(created.analysisId()).isEqualTo(fixture.analysis().analysisId());
        assertThat(created.status()).isEqualTo(AnalysisStatus.CONTEXT_READY);
        assertFourCreationRows(1);
        assertThat(count("analysis_result")).isZero();
        assertThat(count("analysis_message_receipt")).isZero();

        Map<String, Object> run = jdbc.getJdbcTemplate().queryForMap("""
                SELECT status, failure_code, fully_reproducible,
                       reviewer_username, review_task_id, review_task_version,
                       idempotency_key, HEX(request_hash) AS request_hash,
                       created_at, updated_at
                FROM analysis_run
                """);
        assertThat(run)
                .containsEntry("status", "CONTEXT_READY")
                .containsEntry("failure_code", null)
                .containsEntry("fully_reproducible", true)
                .containsEntry("reviewer_username", "reviewer-a")
                .containsEntry("idempotency_key", "request-key-a")
                .containsEntry("request_hash", hex(fixture.analysis().requestHash()));
        assertThat(((Number) run.get("review_task_id")).longValue()).isEqualTo(101L);
        assertThat(((Number) run.get("review_task_version")).longValue()).isZero();
        assertInstant(run, "created_at", fixture.analysis().createdAt());
        assertInstant(run, "updated_at", fixture.analysis().createdAt());

        Map<String, Object> reservation = jdbc.getJdbcTemplate().queryForMap("""
                SELECT created_at, expires_at
                FROM analysis_idempotency_key
                """);
        assertInstant(reservation, "created_at", fixture.analysis().createdAt());
        assertInstant(
                reservation,
                "expires_at",
                SnapshotCodec.toMicrosecondPrecision(fixture.idempotencyExpiresAt())
        );

        Map<String, Object> snapshotRow = jdbc.getJdbcTemplate().queryForMap("""
                SELECT schema_version,
                       CAST(facts_json AS CHAR) AS facts_json,
                       facts_sha256,
                       captured_at,
                       expires_at,
                       deleted_at
                FROM analysis_input_snapshot
                """);
        assertThat(snapshotRow)
                .containsEntry("schema_version", 1L)
                .containsEntry("deleted_at", null);
        byte[] storedHash = (byte[]) snapshotRow.get("facts_sha256");
        assertThat(storedHash).isEqualTo(fixture.snapshot().sha256());
        ReviewTaskContext decoded = SNAPSHOT_CODEC.decodeAndVerify(
                ((String) snapshotRow.get("facts_json")).getBytes(StandardCharsets.UTF_8),
                storedHash
        );
        assertThat(decoded)
                .usingRecursiveComparison()
                .withComparatorForType(BigDecimal::compareTo, BigDecimal.class)
                .isEqualTo(fixture.snapshot().facts());
        assertThat(decoded.capturedAt()).isEqualTo(fixture.snapshot().capturedAt());
        assertThat(decoded.reviewTask().createdAt())
                .isEqualTo(Instant.parse("2026-08-25T01:55:00.111222Z"));
        assertThat(decoded.transaction().occurredAt())
                .isEqualTo(Instant.parse("2026-08-25T01:50:00.999888Z"));
        assertInstant(snapshotRow, "captured_at", fixture.snapshot().capturedAt());
        assertInstant(snapshotRow, "expires_at", fixture.snapshot().expiresAt());
        FactSnapshot reloadedSnapshot = new FactSnapshot(
                fixture.snapshot().schemaVersion(),
                decoded,
                fixture.snapshot().capturedAt(),
                fixture.snapshot().expiresAt(),
                fixture.snapshot().sha256()
        );
        assertThat(SNAPSHOT_CODEC.canonicalBytes(reloadedSnapshot))
                .isEqualTo(SNAPSHOT_CODEC.canonicalBytes(fixture.snapshot()));

        Map<String, Object> outbox = jdbc.getJdbcTemplate().queryForMap("""
                SELECT BIN_TO_UUID(id) AS event_id,
                       BIN_TO_UUID(analysis_id) AS analysis_id,
                       event_type, schema_version,
                       CAST(payload_json AS CHAR) AS payload_json,
                       attempt_count, published_at, last_error_code,
                       next_attempt_at, created_at
                FROM outbox_event
                """);
        assertThat(outbox)
                .containsEntry("event_id", fixture.event().eventId().toString())
                .containsEntry("analysis_id", fixture.analysis().analysisId().toString())
                .containsEntry("event_type", "ANALYSIS_REQUESTED")
                .containsEntry("schema_version", 1L)
                .containsEntry("attempt_count", 0L)
                .containsEntry("published_at", null)
                .containsEntry("last_error_code", null);
        assertInstant(outbox, "created_at", fixture.event().occurredAt());
        assertInstant(outbox, "next_attempt_at", fixture.event().nextAttemptAt());
        assertThat(JSON_MAPPER.readTree(outbox.get("payload_json").toString()))
                .isEqualTo(JSON_MAPPER.createObjectNode()
                        .put("schemaVersion", ((Number) outbox.get("schema_version")).intValue())
                        .put("eventId", outbox.get("event_id").toString())
                        .put("analysisId", outbox.get("analysis_id").toString())
                        .put("occurredAt", fixture.event().occurredAt().toString()));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "analysis_run",
            "analysis_idempotency_key",
            "analysis_input_snapshot",
            "outbox_event"
    })
    void failureAtEveryInsertRollsBackAllFourTables(String failingTable) {
        createFailureTrigger(failingTable);
        CreationFixture fixture = fixture("reviewer-failure", "failure-key");

        assertThatThrownBy(() -> store.create(
                fixture.analysis(),
                fixture.snapshot(),
                fixture.event(),
                fixture.idempotencyExpiresAt()
        ))
                .isInstanceOfSatisfying(AnalysisCreationException.class, exception -> {
                    assertThat(exception.kind())
                            .isEqualTo(AnalysisCreationException.Kind.STORAGE_FAILURE);
                    assertThat(exception).hasMessage("Analysis creation could not be persisted");
                    assertThat(exception).hasNoCause();
                    assertThat(exception.getMessage())
                            .doesNotContain("forced failure", failingTable, "INSERT");
                });

        assertFourCreationRows(0);
    }

    @Test
    void concurrentSameReviewerAndKeyProducesOneSuccessOneConflictWithoutPartialRows()
            throws Exception {
        CreationFixture first = fixture("concurrent-reviewer", "same-key");
        CreationFixture second = fixture("concurrent-reviewer", "same-key");
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            Future<String> firstResult = executor.submit(createAttempt(first, ready, start));
            Future<String> secondResult = executor.submit(createAttempt(second, ready, start));
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            List<String> outcomes = List.of(
                    firstResult.get(15, TimeUnit.SECONDS),
                    secondResult.get(15, TimeUnit.SECONDS)
            );

            assertThat(outcomes).containsExactlyInAnyOrder("SUCCESS", "CONFLICT");
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }

        assertFourCreationRows(1);
        assertThat(jdbc.getJdbcTemplate().queryForObject("""
                SELECT COUNT(*)
                FROM analysis_idempotency_key
                WHERE reviewer_username = 'concurrent-reviewer'
                  AND idempotency_key = 'same-key'
                """, Integer.class)).isEqualTo(1);
    }

    @Test
    void duplicateAnalysisIdIsStorageFailureAndRollsBackSecondCreation() {
        CreationFixture first = fixture("reviewer-first", "first-key");
        store.create(first.analysis(), first.snapshot(), first.event(), first.idempotencyExpiresAt());
        CreationFixture duplicate = fixture(
                "reviewer-second",
                "second-key",
                first.analysis().analysisId(),
                UUID.randomUUID()
        );

        assertStorageFailure(duplicate);

        assertFourCreationRows(1);
    }

    @Test
    void duplicateEventIdIsStorageFailureAndRollsBackSecondCreation() {
        CreationFixture first = fixture("reviewer-first", "first-key");
        store.create(first.analysis(), first.snapshot(), first.event(), first.idempotencyExpiresAt());
        CreationFixture duplicate = fixture(
                "reviewer-second",
                "second-key",
                UUID.randomUUID(),
                first.event().eventId()
        );

        assertStorageFailure(duplicate);

        assertFourCreationRows(1);
    }

    @Test
    void subMicrosecondIdempotencyWindowIsRejectedBeforePersistence() {
        CreationFixture fixture = fixture("reviewer-precision", "precision-key");
        Instant collapsedExpiry = fixture.analysis().createdAt().plusNanos(999);

        assertThatThrownBy(() -> store.create(
                fixture.analysis(),
                fixture.snapshot(),
                fixture.event(),
                collapsedExpiry
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("idempotency expiry must be after creation")
                .hasNoCause();

        assertFourCreationRows(0);
    }

    @Test
    void domainByteArraysAreDefensivelyCopiedAndUseContentValueEquality() {
        byte[] requestHash = repeatedBytes((byte) 7);
        UUID analysisId = UUID.randomUUID();
        NewAnalysis analysis = new NewAnalysis(
                analysisId, "reviewer", 1, 0, "key", requestHash, CREATED_AT);
        NewAnalysis equalAnalysis = new NewAnalysis(
                analysisId, "reviewer", 1, 0, "key",
                Arrays.copyOf(requestHash, requestHash.length), CREATED_AT);
        byte[] unequalHash = Arrays.copyOf(requestHash, requestHash.length);
        unequalHash[0] ^= 1;
        NewAnalysis unequalAnalysis = new NewAnalysis(
                analysisId, "reviewer", 1, 0, "key", unequalHash, CREATED_AT);
        AnalysisRun run = new AnalysisRun(
                analysis.analysisId(), AnalysisStatus.CONTEXT_READY, "reviewer", 1, 0,
                "key", requestHash, null, true, CREATED_AT, CREATED_AT);
        AnalysisRun equalRun = new AnalysisRun(
                analysis.analysisId(), AnalysisStatus.CONTEXT_READY, "reviewer", 1, 0,
                "key", Arrays.copyOf(requestHash, requestHash.length),
                null, true, CREATED_AT, CREATED_AT);
        AnalysisRun unequalRun = new AnalysisRun(
                analysis.analysisId(), AnalysisStatus.CONTEXT_READY, "reviewer", 1, 0,
                "key", unequalHash, null, true, CREATED_AT, CREATED_AT);
        byte[] expectedHash = analysis.requestHash();

        Arrays.fill(requestHash, (byte) 0);
        Arrays.fill(analysis.requestHash(), (byte) 1);
        Arrays.fill(run.requestHash(), (byte) 1);

        assertThat(analysis.requestHash()).isEqualTo(expectedHash);
        assertThat(run.requestHash()).isEqualTo(expectedHash);
        assertThat(equalAnalysis).isEqualTo(analysis);
        assertThat(equalAnalysis.hashCode()).isEqualTo(analysis.hashCode());
        assertThat(unequalAnalysis).isNotEqualTo(analysis);
        assertThat(new HashSet<>(List.of(analysis, equalAnalysis, unequalAnalysis)))
                .hasSize(2);
        assertThat(equalRun).isEqualTo(run);
        assertThat(equalRun.hashCode()).isEqualTo(run.hashCode());
        assertThat(unequalRun).isNotEqualTo(run);
        assertThat(new HashSet<>(List.of(run, equalRun, unequalRun))).hasSize(2);
    }

    @Test
    void persistenceBoundaryDeclaresNoBearerAuthorizationOrTokenParameter() {
        assertThat(Arrays.stream(OutboxRecord.class.getRecordComponents())
                .map(component -> component.getName()))
                .containsExactly(
                        "eventId",
                        "analysisId",
                        "schemaVersion",
                        "occurredAt",
                        "nextAttemptAt"
                );
        assertThat(Arrays.stream(OutboxRecord.class.getRecordComponents())
                .map(component -> component.getType().getName()))
                .doesNotContain(byte[].class.getName(), String.class.getName());

        List<Class<?>> boundaryTypes = List.of(
                AnalysisRunRepository.class,
                JdbcAnalysisRunRepository.class,
                AnalysisCreationStore.class,
                NewAnalysis.class,
                OutboxRecord.class
        );

        for (Class<?> boundaryType : boundaryTypes) {
            List<Parameter> parameters = new ArrayList<>();
            for (Constructor<?> constructor : boundaryType.getDeclaredConstructors()) {
                parameters.addAll(List.of(constructor.getParameters()));
            }
            for (Method method : boundaryType.getDeclaredMethods()) {
                parameters.addAll(List.of(method.getParameters()));
            }
            assertThat(parameters)
                    .allSatisfy(parameter -> assertThat(parameter.getName().toLowerCase())
                            .doesNotContain("bearer", "authorization", "token"));
        }
    }

    private Callable<String> createAttempt(
            CreationFixture fixture,
            CountDownLatch ready,
            CountDownLatch start
    ) {
        return () -> {
            ready.countDown();
            if (!start.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("start gate timed out");
            }
            try {
                store.create(
                        fixture.analysis(),
                        fixture.snapshot(),
                        fixture.event(),
                        fixture.idempotencyExpiresAt()
                );
                return "SUCCESS";
            } catch (AnalysisCreationException exception) {
                assertThat(exception).hasNoCause();
                return exception.kind().name();
            }
        };
    }

    private CreationFixture fixture(String reviewer, String idempotencyKey) {
        return fixture(reviewer, idempotencyKey, UUID.randomUUID(), UUID.randomUUID());
    }

    private CreationFixture fixture(
            String reviewer,
            String idempotencyKey,
            UUID analysisId,
            UUID eventId
    ) {
        byte[] requestHash = sha256("{\"reviewTaskId\":101}");
        NewAnalysis analysis = new NewAnalysis(
                analysisId,
                reviewer,
                101,
                0,
                idempotencyKey,
                requestHash,
                CREATED_AT
        );
        FactSnapshot snapshot = SNAPSHOT_CODEC.capture(context());
        OutboxRecord event = new OutboxRecord(
                eventId,
                analysisId,
                1,
                CREATED_AT,
                CREATED_AT.plusSeconds(2).plusNanos(123)
        );
        return new CreationFixture(
                analysis,
                snapshot,
                event,
                CREATED_AT.plus(24, ChronoUnit.HOURS)
        );
    }

    private static ReviewTaskContext context() {
        return new ReviewTaskContext(
                new ReviewTask(
                        101,
                        0,
                        "PENDING",
                        "HIGH_AMOUNT",
                        "AMOUNT_THRESHOLD_EXCEEDED",
                        Instant.parse("2026-08-25T01:55:00.111222333Z")
                ),
                new ReviewTaskContext.Transaction(
                        202,
                        303,
                        new BigDecimal("120000.00"),
                        "CNY",
                        Instant.parse("2026-08-25T01:50:00.999888777Z"),
                        "CSV_IMPORT"
                ),
                new Reconciliation("SUSPICIOUS", "AMOUNT_AND_TIME", "REQUIRES_REVIEW"),
                List.of(
                        new RiskFact("HIGH_AMOUNT", "120000.00", "TRANSACTION"),
                        new RiskFact("REVIEW_REQUIRED", "true", "RULE")
                ),
                new AccountSummary("ACTIVE", "Primary"),
                CAPTURED_AT
        );
    }

    private void createFailureTrigger(String tableName) {
        String triggerName = "task5_fail_" + tableName;
        jdbc.getJdbcTemplate().execute("""
                CREATE TRIGGER %s
                BEFORE INSERT ON %s
                FOR EACH ROW
                SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'forced failure sentinel'
                """.formatted(triggerName, tableName));
    }

    private void assertStorageFailure(CreationFixture fixture) {
        assertThatThrownBy(() -> store.create(
                fixture.analysis(),
                fixture.snapshot(),
                fixture.event(),
                fixture.idempotencyExpiresAt()
        )).isInstanceOfSatisfying(AnalysisCreationException.class, exception -> {
            assertThat(exception.kind()).isEqualTo(AnalysisCreationException.Kind.STORAGE_FAILURE);
            assertThat(exception).hasMessage("Analysis creation could not be persisted");
            assertThat(exception).hasNoCause();
        });
    }

    private static void assertInstant(Map<String, Object> row, String column, Instant expected) {
        Object stored = row.get(column);
        Instant actual = stored instanceof Timestamp timestamp
                ? timestamp.toInstant()
                : Timestamp.valueOf((LocalDateTime) stored).toInstant();
        assertThat(actual).isEqualTo(expected);
    }

    private void dropFailureTriggers() {
        for (String tableName : List.of(
                "analysis_run",
                "analysis_idempotency_key",
                "analysis_input_snapshot",
                "outbox_event"
        )) {
            jdbc.getJdbcTemplate().execute("DROP TRIGGER IF EXISTS task5_fail_" + tableName);
        }
    }

    private void assertFourCreationRows(int expected) {
        assertThat(count("analysis_run")).isEqualTo(expected);
        assertThat(count("analysis_idempotency_key")).isEqualTo(expected);
        assertThat(count("analysis_input_snapshot")).isEqualTo(expected);
        assertThat(count("outbox_event")).isEqualTo(expected);
    }

    private int count(String tableName) {
        return jdbc.getJdbcTemplate().queryForObject(
                "SELECT COUNT(*) FROM " + tableName,
                Integer.class
        );
    }

    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (Exception exception) {
            throw new IllegalStateException("SHA-256 unavailable");
        }
    }

    private static byte[] repeatedBytes(byte value) {
        byte[] bytes = new byte[32];
        Arrays.fill(bytes, value);
        return bytes;
    }

    private static String hex(byte[] bytes) {
        return java.util.HexFormat.of().withUpperCase().formatHex(bytes);
    }

    private record CreationFixture(
            NewAnalysis analysis,
            FactSnapshot snapshot,
            OutboxRecord event,
            Instant idempotencyExpiresAt
    ) {
    }
}
