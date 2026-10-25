package com.finguard.copilot.usage;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.finguard.copilot.analysis.persistence.JdbcAnalysisRunRepository;
import com.finguard.copilot.model.port.ModelUsage;

@SpringBootTest(properties = {
        "COPILOT_DB_URL=jdbc:mysql://unused/test",
        "COPILOT_DB_USERNAME=test-only",
        "COPILOT_DB_PASSWORD="
})
@Testcontainers
class ModelUsageRecorderIntegrationTest {

    @Container
    @ServiceConnection
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4.10");

    @Autowired
    private JdbcTemplate jdbc;

    private UUID analysisId;
    private ModelUsageRecorder recorder;

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM model_usage_ledger");
        jdbc.update("DELETE FROM analysis_run");
        analysisId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO analysis_run (
                    id, status, reviewer_username, review_task_id, review_task_version,
                    idempotency_key, request_hash, created_at, updated_at, failure_code, fully_reproducible
                ) VALUES (?, 'CONTEXT_READY', 'reviewer', 1, 1, ?, UNHEX(REPEAT('ab', 32)), NOW(6), NOW(6), NULL, TRUE)
                """, JdbcAnalysisRunRepository.uuidBytes(analysisId), "key-" + analysisId);
        recorder = new ModelUsageRecorder(jdbc,
                new ModelCostCalculator.PriceTable("price-v1", "USD", new BigDecimal("0.50"), new BigDecimal("1.50")));
    }

    @Test
    void updatesAPreviousFailureToSuccessWithoutCreatingDuplicateLedgerRows() {
        ModelUsage usage = new ModelUsage(1_200, 180, Duration.ofMillis(240));
        ModelUsage retryUsage = new ModelUsage(1_250, 220, Duration.ofMillis(260));

        recorder.record(analysisId, "qwen", "model-v1", "review-analysis-v1", usage, "FAILURE");
        recorder.record(analysisId, "qwen", "model-v1", "review-analysis-v1", retryUsage, "SUCCESS");
        recorder.record(analysisId, "qwen", "model-v1", "review-analysis-v1", retryUsage, "SUCCESS");

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM model_usage_ledger", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT estimated_cost FROM model_usage_ledger", BigDecimal.class))
                .isEqualByComparingTo("0.00095500");
        assertThat(jdbc.queryForObject("SELECT outcome FROM model_usage_ledger", String.class))
                .isEqualTo("SUCCESS");
        assertThat(jdbc.queryForObject("SELECT price_table_version FROM model_usage_ledger", String.class))
                .isEqualTo("price-v1");
    }
}
