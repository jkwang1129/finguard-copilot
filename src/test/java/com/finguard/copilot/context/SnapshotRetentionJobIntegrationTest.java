package com.finguard.copilot.context;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;

import com.finguard.copilot.analysis.application.GetAnalysisService;
import com.finguard.copilot.config.CopilotProperties;
import com.finguard.copilot.coreclient.port.CoreClient;
import com.finguard.copilot.coreclient.port.CurrentUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "COPILOT_DB_URL=jdbc:mysql://unused/test",
        "COPILOT_DB_USERNAME=test-only",
        "COPILOT_DB_PASSWORD=",
        "finguard.copilot.dispatch.enabled=false",
        "finguard.copilot.jobs.enabled=false",
        "spring.rabbitmq.listener.simple.auto-startup=false",
        "management.health.rabbit.enabled=false"
})
@Testcontainers
class SnapshotRetentionJobIntegrationTest {

    private static final Instant NOW = Instant.parse("2026-08-26T00:00:00Z");

    @Container
    @ServiceConnection
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4.10")
            .withCommand("--log-bin-trust-function-creators=1");

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private CopilotProperties properties;

    @Autowired
    private GetAnalysisService getAnalysisService;

    @MockitoBean
    private CoreClient coreClient;

    @BeforeEach
    void cleanTables() {
        jdbc.update("DELETE FROM analysis_message_receipt");
        jdbc.update("DELETE FROM analysis_result");
        jdbc.update("DELETE FROM outbox_event");
        jdbc.update("DELETE FROM analysis_input_snapshot");
        jdbc.update("DELETE FROM analysis_idempotency_key");
        jdbc.update("DELETE FROM analysis_run");
    }

    @Test
    void removesOnlyExpiredJsonRetainsChecksumAndMarksRunNotFullyReproducible() {
        UUID expired = insertRun("snapshot-expired", "2026-07-26 00:00:00.000000");
        UUID active = insertRun("snapshot-active", "2026-07-27 00:00:00.000000");
        insertSnapshot(expired, "2026-07-26 00:00:00.000000", "2026-08-25 00:00:00.000000");
        insertSnapshot(active, "2026-07-27 00:00:00.000000", "2026-08-26 00:00:00.000001");

        byte[] expiredChecksum = checksum(expired);
        SnapshotRetentionJob job = new SnapshotRetentionJob(
                jdbc, properties, Clock.fixed(NOW, ZoneOffset.UTC));

        assertThat(job.runOnce()).isEqualTo(1);

        Map<String, Object> expiredRow = row(expired);
        assertThat(expiredRow.get("facts_json")).isNull();
        assertThat(expiredRow.get("facts_sha256")).isEqualTo(expiredChecksum);
        assertThat(expiredRow.get("deleted_at")).isNotNull();
        assertThat(jdbc.queryForObject("SELECT fully_reproducible FROM analysis_run WHERE id = UUID_TO_BIN(?)",
                Boolean.class, expired.toString())).isFalse();

        Map<String, Object> activeRow = row(active);
        assertThat(activeRow.get("facts_json")).isNotNull();
        assertThat(activeRow.get("deleted_at")).isNull();
        assertThat(jdbc.queryForObject("SELECT fully_reproducible FROM analysis_run WHERE id = UUID_TO_BIN(?)",
                Boolean.class, active.toString())).isTrue();
    }

    @Test
    void rerunIsIdempotentAndGetPathNeverExposesDeletedSnapshotData() {
        UUID expired = insertRun("snapshot-get", "2026-07-26 00:00:00.000000");
        insertSnapshot(expired, "2026-07-26 00:00:00.000000", "2026-08-25 00:00:00.000000");
        jdbc.update("""
                INSERT INTO analysis_result (analysis_id,schema_version,recommendation,result_json,created_at)
                VALUES (UUID_TO_BIN(?),1,'INSUFFICIENT_EVIDENCE',CAST(? AS JSON),?)
                """, expired.toString(),
                "{\"schemaVersion\":1,\"recommendation\":\"INSUFFICIENT_EVIDENCE\","
                        + "\"summary\":\"safe\",\"facts\":[]}", timestamp("2026-07-26 00:00:00.000000"));
        org.mockito.Mockito.when(coreClient.currentUser("snapshot-token"))
                .thenReturn(new CurrentUser("snapshot-reviewer", java.util.Set.of("REVIEWER")));

        SnapshotRetentionJob job = new SnapshotRetentionJob(
                jdbc, properties, Clock.fixed(NOW, ZoneOffset.UTC));
        assertThat(job.runOnce()).isEqualTo(1);
        assertThat(job.runOnce()).isZero();

        assertThat(jdbc.queryForObject("SELECT facts_json FROM analysis_input_snapshot WHERE analysis_id = UUID_TO_BIN(?)",
                Object.class, expired.toString())).isNull();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM analysis_run WHERE id = UUID_TO_BIN(?)",
                Integer.class, expired.toString())).isEqualTo(1);
        assertThat(getAnalysisService.get(expired, "snapshot-token").result().toString())
                .doesNotContain("snapshot-only");
    }

    private UUID insertRun(String key, String createdAt) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO analysis_run (id,status,reviewer_username,review_task_id,review_task_version,
                    idempotency_key,request_hash,created_at,updated_at,failure_code,fully_reproducible)
                VALUES (UUID_TO_BIN(?),'SUCCEEDED','snapshot-reviewer',101,0,?,UNHEX(REPEAT('ab',32)),
                    ?,?,NULL,TRUE)
                """, id.toString(), key, timestamp(createdAt), timestamp(createdAt));
        return id;
    }

    private void insertSnapshot(UUID id, String capturedAt, String expiresAt) {
        jdbc.update("""
                INSERT INTO analysis_input_snapshot (analysis_id,schema_version,facts_json,facts_sha256,
                    captured_at,expires_at,deleted_at)
                VALUES (UUID_TO_BIN(?),1,CAST(? AS JSON),UNHEX(REPEAT('cd',32)),?,?,NULL)
                """, id.toString(), "{\"sensitive\":\"snapshot-only\"}",
                timestamp(capturedAt), timestamp(expiresAt));
    }

    private Map<String, Object> row(UUID id) {
        return jdbc.queryForMap("""
                SELECT facts_json, facts_sha256, deleted_at FROM analysis_input_snapshot
                WHERE analysis_id = UUID_TO_BIN(?)
                """, id.toString());
    }

    private byte[] checksum(UUID id) {
        return jdbc.queryForObject("SELECT facts_sha256 FROM analysis_input_snapshot WHERE analysis_id = UUID_TO_BIN(?)",
                byte[].class, id.toString());
    }

    private static Timestamp timestamp(String value) {
        return Timestamp.from(Instant.parse(value.replace(' ', 'T') + "Z"));
    }
}
