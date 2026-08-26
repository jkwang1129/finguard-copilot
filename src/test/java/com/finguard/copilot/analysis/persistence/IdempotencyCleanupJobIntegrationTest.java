package com.finguard.copilot.analysis.persistence;

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
class IdempotencyCleanupJobIntegrationTest {

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
    void deletesExpiredReservationsByTheirExactExpiryAndRetainsRuns() {
        UUID expired = insertRunAndReservation("expired", "2026-08-25 23:59:59.999999");
        UUID atBoundary = insertRunAndReservation("boundary", "2026-08-26 00:00:00.000000");
        UUID active = insertRunAndReservation("active", "2026-08-26 00:00:00.000001");

        IdempotencyCleanupJob job = new IdempotencyCleanupJob(
                jdbc, properties, Clock.fixed(NOW, ZoneOffset.UTC));

        assertThat(job.runOnce()).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM analysis_idempotency_key", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM analysis_run", Integer.class)).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM analysis_idempotency_key WHERE analysis_id = UUID_TO_BIN(?)",
                Integer.class, active.toString())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM analysis_run WHERE id IN (UUID_TO_BIN(?),UUID_TO_BIN(?))",
                Integer.class, expired.toString(), atBoundary.toString())).isEqualTo(2);
    }

    @Test
    void cleanupDoesNotDeleteActiveReservationOnRepeatedRun() {
        insertRunAndReservation("active-only", "2026-08-26 00:00:00.000001");
        IdempotencyCleanupJob job = new IdempotencyCleanupJob(
                jdbc, properties, Clock.fixed(NOW, ZoneOffset.UTC));

        assertThat(job.runOnce()).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM analysis_idempotency_key", Integer.class)).isEqualTo(1);
    }

    private UUID insertRunAndReservation(String key, String expiresAt) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO analysis_run (id,status,reviewer_username,review_task_id,review_task_version,
                    idempotency_key,request_hash,created_at,updated_at,failure_code,fully_reproducible)
                VALUES (UUID_TO_BIN(?),'CONTEXT_READY','idempotency-reviewer',101,0,?,UNHEX(REPEAT('ab',32)),
                    '2026-08-25 00:00:00.000000','2026-08-25 00:00:00.000000',NULL,TRUE)
                """, id.toString(), key);
        jdbc.update("""
                INSERT INTO analysis_idempotency_key
                    (analysis_id,reviewer_username,idempotency_key,request_hash,expires_at,created_at)
                VALUES (UUID_TO_BIN(?),'idempotency-reviewer',?,UNHEX(REPEAT('ab',32)),?,'2026-08-25 00:00:00.000000')
                """, id.toString(), key, timestamp(expiresAt));
        return id;
    }

    private static Timestamp timestamp(String value) {
        return Timestamp.from(Instant.parse(value.replace(' ', 'T') + "Z"));
    }
}
