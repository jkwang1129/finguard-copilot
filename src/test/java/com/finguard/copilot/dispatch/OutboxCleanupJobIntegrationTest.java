package com.finguard.copilot.dispatch;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import com.finguard.copilot.config.CopilotProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
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
class OutboxCleanupJobIntegrationTest {

    private static final Instant NOW = Instant.parse("2026-08-26T00:00:00Z");

    @Container
    @ServiceConnection
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4.10")
            .withCommand("--log-bin-trust-function-creators=1");

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private CopilotProperties properties;

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
    void deletesOnlyPublishedEventsStrictlyOlderThanSevenDays() {
        UUID oldPublished = insertEvent("2026-08-18 23:59:59.999999", "2026-08-18 23:59:59.999999", true);
        UUID boundaryPublished = insertEvent("2026-08-19 00:00:00.000000", "2026-08-19 00:00:00.000000", true);
        UUID oldUnpublished = insertEvent("2026-08-18 23:59:59.999999", null, false);

        OutboxCleanupJob job = new OutboxCleanupJob(
                jdbc, properties, Clock.fixed(NOW, ZoneOffset.UTC));

        assertThat(job.runOnce()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM outbox_event", Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM outbox_event WHERE id = UUID_TO_BIN(?)",
                Integer.class, oldPublished.toString())).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM outbox_event WHERE id IN (UUID_TO_BIN(?),UUID_TO_BIN(?))",
                Integer.class, boundaryPublished.toString(), oldUnpublished.toString())).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM outbox_event WHERE published_at IS NULL",
                Integer.class)).isEqualTo(1);
    }

    @Test
    void cleanupNeverDeletesAnalysisRunsWithThePublishedEvent() {
        UUID old = insertEvent("2026-08-18 00:00:00.000000", "2026-08-18 00:00:00.000000", true);
        UUID analysisId = UUID.fromString(jdbc.queryForObject(
                "SELECT BIN_TO_UUID(analysis_id) FROM outbox_event WHERE id = UUID_TO_BIN(?)",
                String.class, old.toString()));
        OutboxCleanupJob job = new OutboxCleanupJob(
                jdbc, properties, Clock.fixed(NOW, ZoneOffset.UTC));

        job.runOnce();

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM analysis_run WHERE id = UUID_TO_BIN(?)",
                Integer.class, analysisId.toString())).isEqualTo(1);
    }

    private UUID insertEvent(String createdAt, String publishedAt, boolean published) {
        UUID analysisId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO analysis_run (id,status,reviewer_username,review_task_id,review_task_version,
                    idempotency_key,request_hash,created_at,updated_at,failure_code,fully_reproducible)
                VALUES (UUID_TO_BIN(?),'SUCCEEDED','outbox-reviewer',101,0,?,UNHEX(REPEAT('ab',32)),
                    ?,?,NULL,TRUE)
                """, analysisId.toString(), eventId.toString(), timestamp(createdAt), timestamp(createdAt));
        jdbc.update("""
                INSERT INTO outbox_event (id,analysis_id,event_type,schema_version,payload_json,attempt_count,
                    next_attempt_at,published_at,last_error_code,created_at)
                VALUES (UUID_TO_BIN(?),UUID_TO_BIN(?),'ANALYSIS_REQUESTED',1,
                    CAST(? AS JSON),0,?, ?,NULL,?)
                """, eventId.toString(), analysisId.toString(),
                "{\"schemaVersion\":1,\"eventId\":\"" + eventId + "\",\"analysisId\":\"" + analysisId
                        + "\",\"occurredAt\":\"" + createdAt.replace(' ', 'T') + "Z\"}",
                timestamp(createdAt), publishedAt == null ? null : timestamp(publishedAt), timestamp(createdAt));
        return eventId;
    }

    private static Timestamp timestamp(String value) {
        return Timestamp.from(Instant.parse(value.replace(' ', 'T') + "Z"));
    }
}
