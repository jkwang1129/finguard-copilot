package com.finguard.copilot;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(properties = {
        "COPILOT_DB_URL=jdbc:mysql://unused/test",
        "COPILOT_DB_USERNAME=test-only",
        "COPILOT_DB_PASSWORD="
})
@Testcontainers
class Phase1MigrationTest {

    private static final List<String> PHASE1_TABLES = List.of(
            "analysis_idempotency_key",
            "analysis_input_snapshot",
            "analysis_message_receipt",
            "analysis_result",
            "outbox_event"
    );

    @Container
    @ServiceConnection
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4.10");

    @Autowired
    private DataSource dataSource;

    @Test
    void additiveMigrationPreservesV1ColumnsAndExtendsAnalysisRun() throws SQLException {
        assertThat(columns("analysis_run")).containsExactlyEntriesOf(linkedMap(
                "id", "binary(16)|NO",
                "status", "varchar(32)|NO",
                "reviewer_username", "varchar(100)|NO",
                "review_task_id", "bigint unsigned|NO",
                "review_task_version", "bigint unsigned|NO",
                "idempotency_key", "varchar(100)|NO",
                "request_hash", "binary(32)|NO",
                "created_at", "datetime(6)|NO",
                "updated_at", "datetime(6)|NO",
                "failure_code", "varchar(64)|YES",
                "fully_reproducible", "tinyint(1)|NO"
        ));

        assertThat(indexes("analysis_run")).containsEntry(
                "PRIMARY", List.of("id"));
        assertThat(indexes("analysis_run")).containsEntry(
                "idx_analysis_run_review_task", List.of("review_task_id", "review_task_version"));
        assertThat(indexes("analysis_run"))
                .doesNotContainKey("uk_analysis_run_reviewer_idempotency");
    }

    @Test
    void createsExactlyThePhase1TablesWithRequiredColumnTypes() throws SQLException {
        assertThat(phase1Tables()).containsExactlyElementsOf(PHASE1_TABLES);

        assertThat(columns("analysis_idempotency_key")).containsExactlyEntriesOf(linkedMap(
                "analysis_id", "binary(16)|NO",
                "reviewer_username", "varchar(100)|NO",
                "idempotency_key", "varchar(100)|NO",
                "request_hash", "binary(32)|NO",
                "expires_at", "datetime(6)|NO",
                "created_at", "datetime(6)|NO"
        ));
        assertThat(columns("analysis_input_snapshot")).containsExactlyEntriesOf(linkedMap(
                "analysis_id", "binary(16)|NO",
                "schema_version", "int unsigned|NO",
                "facts_json", "json|YES",
                "facts_sha256", "binary(32)|NO",
                "captured_at", "datetime(6)|NO",
                "expires_at", "datetime(6)|NO",
                "deleted_at", "datetime(6)|YES"
        ));
        assertThat(columns("analysis_result")).containsExactlyEntriesOf(linkedMap(
                "analysis_id", "binary(16)|NO",
                "schema_version", "int unsigned|NO",
                "recommendation", "varchar(32)|NO",
                "result_json", "json|NO",
                "created_at", "datetime(6)|NO"
        ));
        assertThat(columns("outbox_event")).containsExactlyEntriesOf(linkedMap(
                "id", "binary(16)|NO",
                "analysis_id", "binary(16)|NO",
                "event_type", "varchar(64)|NO",
                "schema_version", "int unsigned|NO",
                "payload_json", "json|NO",
                "attempt_count", "int unsigned|NO",
                "next_attempt_at", "datetime(6)|NO",
                "published_at", "datetime(6)|YES",
                "last_error_code", "varchar(64)|YES",
                "created_at", "datetime(6)|NO"
        ));
        assertThat(columns("analysis_message_receipt")).containsExactlyEntriesOf(linkedMap(
                "event_id", "binary(16)|NO",
                "analysis_id", "binary(16)|NO",
                "received_at", "datetime(6)|NO"
        ));
    }

    @Test
    void enforcesPrimaryUniqueAndDueIndexes() throws SQLException {
        assertThat(indexes("analysis_idempotency_key"))
                .containsEntry("PRIMARY", List.of("analysis_id"))
                .containsEntry("uk_analysis_idempotency_reviewer_key",
                        List.of("reviewer_username", "idempotency_key"))
                .containsEntry("idx_analysis_idempotency_expires", List.of("expires_at"));
        assertThat(indexes("analysis_input_snapshot"))
                .containsEntry("PRIMARY", List.of("analysis_id"))
                .containsEntry("idx_analysis_snapshot_expiry", List.of("expires_at", "deleted_at"));
        assertThat(indexes("analysis_result"))
                .containsEntry("PRIMARY", List.of("analysis_id"));
        assertThat(indexes("outbox_event"))
                .containsEntry("PRIMARY", List.of("id"))
                .containsEntry("idx_outbox_event_due",
                        List.of("published_at", "next_attempt_at", "created_at"));
        assertThat(indexes("analysis_message_receipt"))
                .containsEntry("PRIMARY", List.of("event_id"));

        assertThat(indexNonUnique(
                "analysis_idempotency_key",
                "uk_analysis_idempotency_reviewer_key"
        )).isZero();
    }

    @Test
    void idempotencyKeyUsesBinaryCollationAndTreatsCasePairsAsDistinct() throws SQLException {
        assertThat(columnCollation("analysis_idempotency_key", "idempotency_key"))
                .isEqualTo("ascii_bin");

        UUID firstAnalysisId = UUID.randomUUID();
        UUID secondAnalysisId = UUID.randomUUID();
        try (Connection connection = dataSource.getConnection()) {
            insertRun(connection, firstAnalysisId, 9001);
            insertRun(connection, secondAnalysisId, 9002);
            insertReservation(connection, firstAnalysisId, "Case-Key");
            insertReservation(connection, secondAnalysisId, "case-key");
        }

        assertThat(strings("""
                SELECT idempotency_key
                FROM analysis_idempotency_key
                WHERE reviewer_username = 'case-sensitive-reviewer'
                ORDER BY idempotency_key COLLATE ascii_bin
                """)).containsExactly("Case-Key", "case-key");
    }

    @Test
    void phase1ChildrenReferenceAnalysisRunWithRestrictDeletion() throws SQLException {
        assertThat(foreignKeys()).containsExactlyInAnyOrderEntriesOf(Map.of(
                "analysis_idempotency_key.fk_analysis_idempotency_run",
                "analysis_id->analysis_run.id|RESTRICT",
                "analysis_input_snapshot.fk_analysis_snapshot_run",
                "analysis_id->analysis_run.id|RESTRICT",
                "analysis_result.fk_analysis_result_run",
                "analysis_id->analysis_run.id|RESTRICT",
                "outbox_event.fk_outbox_event_run",
                "analysis_id->analysis_run.id|RESTRICT",
                "analysis_message_receipt.fk_analysis_receipt_run",
                "analysis_id->analysis_run.id|RESTRICT"
        ));
    }

    @Test
    void enforcesRunStatusesFailureCodeReproducibilityAndJsonLifecycleChecks() throws SQLException {
        Map<String, String> checks = checkConstraints();

        assertThat(checks.get("ck_analysis_run_status"))
                .contains("context_ready", "queued", "retrieval_ready", "generating",
                        "validating", "succeeded", "failed");
        assertThat(checks.get("ck_analysis_run_failure"))
                .contains("failure_code", "failed", "is null", "is not null");
        assertThat(checks.get("ck_analysis_run_reproducible"))
                .contains("fully_reproducible", "0", "1");
        assertThat(checks.get("ck_analysis_snapshot_lifecycle"))
                .contains("facts_json", "deleted_at", "is null", "is not null");
        assertThat(checks.get("ck_analysis_snapshot_deleted_after_expiry"))
                .contains("deleted_at", "expires_at", ">=");
        assertThat(checks.get("ck_analysis_snapshot_expiry"))
                .contains("captured_at", "expires_at");
        assertThat(checks.get("ck_analysis_result_recommendation"))
                .contains("confirmed", "ignored", "insufficient_evidence");
        assertThat(checks.get("ck_outbox_event_type")).contains("analysis_requested");
        assertThat(checks.get("ck_outbox_last_error_code"))
                .contains("last_error_code", "char_length", "64");
    }

    @Test
    void snapshotJsonCanBeDeletedOnlyAtOrAfterExpiry() throws SQLException {
        UUID analysisId = UUID.randomUUID();
        try (Connection connection = dataSource.getConnection()) {
            insertRun(connection, analysisId, 9003);
            try (PreparedStatement insert = connection.prepareStatement("""
                    INSERT INTO analysis_input_snapshot (
                        analysis_id, schema_version, facts_json, facts_sha256,
                        captured_at, expires_at, deleted_at
                    ) VALUES (
                        UUID_TO_BIN(?), 1, JSON_OBJECT('fact', 'original'),
                        UNHEX(REPEAT('22', 32)),
                        '2026-08-25 00:00:00.000000',
                        '2026-09-24 00:00:00.000000', NULL
                    )
                    """)) {
                insert.setString(1, analysisId.toString());
                assertThat(insert.executeUpdate()).isEqualTo(1);
            }

            assertThatThrownBy(() -> updateSnapshotDeletion(
                    connection, analysisId, "2026-09-23 23:59:59.999999"))
                    .isInstanceOf(SQLException.class);

            assertThat(updateSnapshotDeletion(
                    connection, analysisId, "2026-09-24 00:00:00.000000"))
                    .isEqualTo(1);
        }
    }

    private List<String> phase1Tables() throws SQLException {
        return strings("""
                SELECT table_name
                FROM information_schema.tables
                WHERE table_schema = DATABASE()
                  AND table_name IN (
                    'analysis_idempotency_key',
                    'analysis_input_snapshot',
                    'analysis_message_receipt',
                    'analysis_result',
                    'outbox_event'
                  )
                ORDER BY table_name
                """);
    }

    private Map<String, String> columns(String tableName) throws SQLException {
        return pairs("""
                SELECT column_name, CONCAT(column_type, '|', is_nullable)
                FROM information_schema.columns
                WHERE table_schema = DATABASE()
                  AND table_name = '%s'
                ORDER BY ordinal_position
                """.formatted(tableName));
    }

    private Map<String, List<String>> indexes(String tableName) throws SQLException {
        Map<String, List<String>> result = new LinkedHashMap<>();
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("""
                     SELECT index_name, column_name
                     FROM information_schema.statistics
                     WHERE table_schema = DATABASE()
                       AND table_name = '%s'
                     ORDER BY index_name, seq_in_index
                     """.formatted(tableName))) {
            while (rows.next()) {
                result.computeIfAbsent(rows.getString(1), ignored -> new ArrayList<>())
                        .add(rows.getString(2));
            }
        }
        return result;
    }

    private int indexNonUnique(String tableName, String indexName) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT DISTINCT non_unique
                     FROM information_schema.statistics
                     WHERE table_schema = DATABASE()
                       AND table_name = ?
                       AND index_name = ?
                     """)) {
            statement.setString(1, tableName);
            statement.setString(2, indexName);
            try (ResultSet rows = statement.executeQuery()) {
                assertThat(rows.next()).isTrue();
                int result = rows.getInt(1);
                assertThat(rows.next()).isFalse();
                return result;
            }
        }
    }

    private String columnCollation(String tableName, String columnName) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT collation_name
                     FROM information_schema.columns
                     WHERE table_schema = DATABASE()
                       AND table_name = ?
                       AND column_name = ?
                     """)) {
            statement.setString(1, tableName);
            statement.setString(2, columnName);
            try (ResultSet rows = statement.executeQuery()) {
                assertThat(rows.next()).isTrue();
                return rows.getString(1);
            }
        }
    }

    private static void insertRun(Connection connection, UUID analysisId, long reviewTaskId)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO analysis_run (
                    id, status, reviewer_username, review_task_id, review_task_version,
                    idempotency_key, request_hash, created_at, updated_at,
                    failure_code, fully_reproducible
                ) VALUES (
                    UUID_TO_BIN(?), 'CONTEXT_READY', 'case-sensitive-reviewer', ?, 0,
                    ?, UNHEX(REPEAT('11', 32)),
                    '2026-08-25 00:00:00.000000', '2026-08-25 00:00:00.000000',
                    NULL, TRUE
                )
                """)) {
            statement.setString(1, analysisId.toString());
            statement.setLong(2, reviewTaskId);
            statement.setString(3, "run-key-" + analysisId);
            assertThat(statement.executeUpdate()).isEqualTo(1);
        }
    }

    private static void insertReservation(
            Connection connection,
            UUID analysisId,
            String idempotencyKey
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO analysis_idempotency_key (
                    analysis_id, reviewer_username, idempotency_key,
                    request_hash, expires_at, created_at
                ) VALUES (
                    UUID_TO_BIN(?), 'case-sensitive-reviewer', ?,
                    UNHEX(REPEAT('11', 32)),
                    '2026-08-26 00:00:00.000000', '2026-08-25 00:00:00.000000'
                )
                """)) {
            statement.setString(1, analysisId.toString());
            statement.setString(2, idempotencyKey);
            assertThat(statement.executeUpdate()).isEqualTo(1);
        }
    }

    private static int updateSnapshotDeletion(
            Connection connection,
            UUID analysisId,
            String deletedAt
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE analysis_input_snapshot
                SET facts_json = NULL,
                    deleted_at = ?
                WHERE analysis_id = UUID_TO_BIN(?)
                """)) {
            statement.setString(1, deletedAt);
            statement.setString(2, analysisId.toString());
            return statement.executeUpdate();
        }
    }

    private Map<String, String> foreignKeys() throws SQLException {
        return pairs("""
                SELECT CONCAT(kcu.table_name, '.', kcu.constraint_name),
                       CONCAT(kcu.column_name, '->', kcu.referenced_table_name, '.',
                              kcu.referenced_column_name, '|', rc.delete_rule)
                FROM information_schema.key_column_usage kcu
                JOIN information_schema.referential_constraints rc
                  ON rc.constraint_schema = kcu.constraint_schema
                 AND rc.constraint_name = kcu.constraint_name
                 AND rc.table_name = kcu.table_name
                WHERE kcu.constraint_schema = DATABASE()
                  AND kcu.table_name IN (
                    'analysis_idempotency_key',
                    'analysis_input_snapshot',
                    'analysis_message_receipt',
                    'analysis_result',
                    'outbox_event'
                  )
                  AND kcu.referenced_table_name IS NOT NULL
                ORDER BY kcu.table_name, kcu.constraint_name
                """);
    }

    private Map<String, String> checkConstraints() throws SQLException {
        Map<String, String> checks = pairs("""
                SELECT cc.constraint_name, LOWER(REPLACE(cc.check_clause, '`', ''))
                FROM information_schema.check_constraints cc
                JOIN information_schema.table_constraints tc
                  ON tc.constraint_schema = cc.constraint_schema
                 AND tc.constraint_name = cc.constraint_name
                WHERE cc.constraint_schema = DATABASE()
                  AND tc.table_name IN (
                    'analysis_run',
                    'analysis_input_snapshot',
                    'analysis_result',
                    'outbox_event'
                  )
                ORDER BY cc.constraint_name
                """);
        return checks;
    }

    private List<String> strings(String sql) throws SQLException {
        List<String> result = new ArrayList<>();
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(sql)) {
            while (rows.next()) {
                result.add(rows.getString(1));
            }
        }
        return result;
    }

    private Map<String, String> pairs(String sql) throws SQLException {
        Map<String, String> result = new LinkedHashMap<>();
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(sql)) {
            while (rows.next()) {
                result.put(rows.getString(1), rows.getString(2));
            }
        }
        return result;
    }

    private static Map<String, String> linkedMap(String... entries) {
        Map<String, String> result = new LinkedHashMap<>();
        for (int index = 0; index < entries.length; index += 2) {
            result.put(entries[index], entries[index + 1]);
        }
        return result;
    }
}
