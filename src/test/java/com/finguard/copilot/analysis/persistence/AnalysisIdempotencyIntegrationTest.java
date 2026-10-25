package com.finguard.copilot.analysis.persistence;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import com.finguard.copilot.analysis.application.CreateAnalysisCommand;
import com.finguard.copilot.analysis.application.CreateAnalysisService;
import com.finguard.copilot.analysis.domain.AnalysisStatus;
import com.finguard.copilot.api.analysis.AnalysisAcceptedResponse;
import com.finguard.copilot.common.error.CopilotException;
import com.finguard.copilot.common.error.ErrorCode;
import com.finguard.copilot.context.SnapshotCodec;
import com.finguard.copilot.coreclient.port.CoreClient;
import com.finguard.copilot.coreclient.port.CurrentUser;
import com.finguard.copilot.coreclient.port.ReviewTaskContext;
import com.finguard.copilot.coreclient.port.ReviewTaskContext.AccountSummary;
import com.finguard.copilot.coreclient.port.ReviewTaskContext.Reconciliation;
import com.finguard.copilot.coreclient.port.ReviewTaskContext.ReviewTask;
import com.finguard.copilot.coreclient.port.ReviewTaskContext.RiskFact;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@SpringBootTest(properties = {
        "COPILOT_DB_URL=jdbc:mysql://unused/test",
        "COPILOT_DB_USERNAME=test-only",
        "COPILOT_DB_PASSWORD=",
        "spring.datasource.hikari.maximum-pool-size=8",
        "spring.datasource.hikari.connection-timeout=5000"
})
@Testcontainers
class AnalysisIdempotencyIntegrationTest {

    private static final String REVIEWER_TOKEN = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxIn0.signature";
    private static final String OTHER_TOKEN = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIyIn0.signature";
    private static final Instant CAPTURED_AT = Instant.parse("2026-08-25T02:03:04.123456Z");

    @Container
    @ServiceConnection
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4.10")
            .withCommand("--log-bin-trust-function-creators=1");

    @Autowired
    private CreateAnalysisService service;

    @Autowired
    private NamedParameterJdbcTemplate jdbc;

    @MockitoBean
    private CoreClient coreClient;

    @BeforeEach
    void cleanTables() {
        jdbc.getJdbcTemplate().update("DELETE FROM analysis_message_receipt");
        jdbc.getJdbcTemplate().update("DELETE FROM analysis_result");
        jdbc.getJdbcTemplate().update("DELETE FROM outbox_event");
        jdbc.getJdbcTemplate().update("DELETE FROM analysis_input_snapshot");
        jdbc.getJdbcTemplate().update("DELETE FROM analysis_idempotency_key");
        jdbc.getJdbcTemplate().update("DELETE FROM analysis_run");
        when(coreClient.currentUser(REVIEWER_TOKEN)).thenReturn(new CurrentUser("reviewer", Set.of("REVIEWER")));
        when(coreClient.currentUser(OTHER_TOKEN)).thenReturn(new CurrentUser("other", Set.of("REVIEWER")));
        when(coreClient.reviewTaskContext(anyLong(), anyString())).thenAnswer(invocation ->
                context(invocation.getArgument(0, Long.class)));
    }

    @Test
    void sameReviewerAndKeyAndHashWithin24HoursReturnsOriginalIdAsReplay() {
        AnalysisAcceptedResponse first = service.create(new CreateAnalysisCommand(101, "same-key"), REVIEWER_TOKEN);
        AnalysisAcceptedResponse replay = service.create(new CreateAnalysisCommand(101, "same-key"), REVIEWER_TOKEN);

        assertThat(replay.analysisId()).isEqualTo(first.analysisId());
        assertThat(replay.replayed()).isTrue();
        assertFourRows(1);
    }

    @Test
    void sameKeyWithDifferentTaskReturnsConflict() {
        service.create(new CreateAnalysisCommand(101, "same-key"), REVIEWER_TOKEN);

        assertThatThrownBy(() -> service.create(new CreateAnalysisCommand(102, "same-key"), REVIEWER_TOKEN))
                .isInstanceOf(CopilotException.class)
                .extracting(exception -> ((CopilotException) exception).code())
                .isEqualTo(ErrorCode.CONFLICT);

        assertFourRows(1);
    }

    @Test
    void sameKeyIsIndependentAcrossReviewers() {
        AnalysisAcceptedResponse first = service.create(new CreateAnalysisCommand(101, "same-key"), REVIEWER_TOKEN);
        AnalysisAcceptedResponse second = service.create(new CreateAnalysisCommand(101, "same-key"), OTHER_TOKEN);

        assertThat(second.analysisId()).isNotEqualTo(first.analysisId());
        assertThat(second.replayed()).isFalse();
        assertFourRows(2);
    }

    @Test
    void expiredReservationAllowsNewAnalysisId() {
        AnalysisAcceptedResponse first = service.create(new CreateAnalysisCommand(101, "expired-key"), REVIEWER_TOKEN);
        jdbc.getJdbcTemplate().update("UPDATE analysis_idempotency_key SET created_at = CURRENT_TIMESTAMP(6) - INTERVAL 2 DAY, expires_at = CURRENT_TIMESTAMP(6) - INTERVAL 1 SECOND");

        AnalysisAcceptedResponse second = service.create(new CreateAnalysisCommand(101, "expired-key"), REVIEWER_TOKEN);

        assertThat(second.analysisId()).isNotEqualTo(first.analysisId());
        assertThat(second.replayed()).isFalse();
        assertThat(jdbc.getJdbcTemplate().queryForObject(
                "SELECT COUNT(*) FROM analysis_run", Integer.class)).isEqualTo(2);
        assertThat(jdbc.getJdbcTemplate().queryForObject(
                "SELECT COUNT(*) FROM analysis_idempotency_key", Integer.class)).isEqualTo(1);
    }

    @Test
    void concurrentSameReviewerAndKeyProducesOneRunAndOneReplay() throws Exception {
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Callable<AnalysisAcceptedResponse> attempt = () -> {
                ready.countDown();
                assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
                assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
                return service.create(new CreateAnalysisCommand(101, "concurrent-key"), REVIEWER_TOKEN);
            };
            Future<AnalysisAcceptedResponse> first = executor.submit(attempt);
            Future<AnalysisAcceptedResponse> second = executor.submit(attempt);
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            AnalysisAcceptedResponse firstResult = first.get(20, TimeUnit.SECONDS);
            AnalysisAcceptedResponse secondResult = second.get(20, TimeUnit.SECONDS);
            assertThat(List.of(firstResult.analysisId(), secondResult.analysisId()))
                    .containsExactly(firstResult.analysisId(), firstResult.analysisId());
            assertThat(List.of(firstResult.replayed(), secondResult.replayed()))
                    .containsExactlyInAnyOrder(false, true);
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
        assertFourRows(1);
    }

    @Test
    void concurrentReplacementOfExpiredReservationProducesOneNewRunAndOneReplay() throws Exception {
        AnalysisAcceptedResponse original = service.create(
                new CreateAnalysisCommand(101, "expired-concurrent-key"), REVIEWER_TOKEN);
        jdbc.getJdbcTemplate().update("""
                UPDATE analysis_idempotency_key
                SET created_at = CURRENT_TIMESTAMP(6) - INTERVAL 2 DAY,
                    expires_at = CURRENT_TIMESTAMP(6) - INTERVAL 1 SECOND
                WHERE analysis_id = UUID_TO_BIN(?)
                """, original.analysisId().toString());

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Callable<AnalysisAcceptedResponse> attempt = () -> {
                ready.countDown();
                assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
                assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
                return service.create(
                        new CreateAnalysisCommand(101, "expired-concurrent-key"), REVIEWER_TOKEN);
            };
            Future<AnalysisAcceptedResponse> first = executor.submit(attempt);
            Future<AnalysisAcceptedResponse> second = executor.submit(attempt);
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            AnalysisAcceptedResponse firstResult = first.get(20, TimeUnit.SECONDS);
            AnalysisAcceptedResponse secondResult = second.get(20, TimeUnit.SECONDS);
            assertThat(firstResult.analysisId()).isEqualTo(secondResult.analysisId());
            assertThat(List.of(firstResult.replayed(), secondResult.replayed()))
                    .containsExactlyInAnyOrder(false, true);
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }

        assertThat(jdbc.getJdbcTemplate().queryForObject(
                "SELECT COUNT(*) FROM analysis_run", Integer.class)).isEqualTo(2);
        assertThat(jdbc.getJdbcTemplate().queryForObject(
                "SELECT COUNT(*) FROM analysis_idempotency_key", Integer.class)).isEqualTo(1);
    }

    private void assertFourRows(int expected) {
        assertThat(jdbc.getJdbcTemplate().queryForObject("SELECT COUNT(*) FROM analysis_run", Integer.class)).isEqualTo(expected);
        assertThat(jdbc.getJdbcTemplate().queryForObject("SELECT COUNT(*) FROM analysis_idempotency_key", Integer.class)).isEqualTo(expected);
        assertThat(jdbc.getJdbcTemplate().queryForObject("SELECT COUNT(*) FROM analysis_input_snapshot", Integer.class)).isEqualTo(expected);
        assertThat(jdbc.getJdbcTemplate().queryForObject("SELECT COUNT(*) FROM outbox_event", Integer.class)).isEqualTo(expected);
    }

    private static ReviewTaskContext context(long taskId) {
        return new ReviewTaskContext(
                new ReviewTask(taskId, 0, "PENDING", "HIGH_AMOUNT", "REQUIRES_REVIEW", CAPTURED_AT),
                new ReviewTaskContext.Transaction(202, 303, new BigDecimal("120000.00"), "CNY", CAPTURED_AT, "CSV_IMPORT"),
                new Reconciliation("SUSPICIOUS", "AMOUNT_AND_TIME", "REQUIRES_REVIEW"),
                List.of(new RiskFact("HIGH_AMOUNT", "120000.00", "TRANSACTION")),
                new AccountSummary("ACTIVE", "Primary"), CAPTURED_AT);
    }
}
