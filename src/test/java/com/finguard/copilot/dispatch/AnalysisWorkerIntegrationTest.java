package com.finguard.copilot.dispatch;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.finguard.copilot.analysis.application.AnalysisOrchestrator;
import com.finguard.copilot.analysis.application.CreateAnalysisCommand;
import com.finguard.copilot.analysis.application.CreateAnalysisService;
import com.finguard.copilot.api.analysis.AnalysisAcceptedResponse;
import com.finguard.copilot.analysis.persistence.AnalysisRunRepository;
import com.finguard.copilot.config.CopilotProperties;
import com.finguard.copilot.coreclient.port.CoreClient;
import com.finguard.copilot.coreclient.port.CurrentUser;
import com.finguard.copilot.coreclient.port.ReviewTaskContext;
import com.finguard.copilot.context.SnapshotCodec;
import com.finguard.copilot.guardrail.AnalysisOutputSchemaValidator;
import com.finguard.copilot.guardrail.FactConsistencyValidator;
import org.springframework.amqp.core.MessagePostProcessor;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@SpringBootTest(properties = {
        "COPILOT_DB_URL=jdbc:mysql://unused/test",
        "COPILOT_DB_USERNAME=test-only",
        "COPILOT_DB_PASSWORD=",
        "finguard.copilot.dispatch.enabled=true",
        "finguard.copilot.dispatch.relay-enabled=false"
})
@Testcontainers
class AnalysisWorkerIntegrationTest {

    private static final String REVIEWER_TOKEN =
            "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxIn0.signature";
    private static final Instant CAPTURED_AT = Instant.parse("2026-08-25T02:03:04.123456Z");

    @Container
    @ServiceConnection
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4.10")
            .withCommand("--log-bin-trust-function-creators=1");

    @Container
    @ServiceConnection
    static final RabbitMQContainer RABBIT = new RabbitMQContainer("rabbitmq:4.0-management");

    @Autowired
    private CreateAnalysisService createAnalysisService;

    @Autowired
    private AnalysisWorker worker;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private AnalysisRunRepository runRepository;

    @Autowired
    private AnalysisOrchestrator productionOrchestrator;

    @Autowired
    private AnalysisOutputSchemaValidator schemaValidator;

    @Autowired
    private FactConsistencyValidator factValidator;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private CopilotProperties copilotProperties;

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
        when(coreClient.currentUser(REVIEWER_TOKEN))
                .thenReturn(new CurrentUser("worker-reviewer", Set.of("REVIEWER")));
        when(coreClient.reviewTaskContext(anyLong(), anyString()))
                .thenAnswer(invocation -> context(invocation.getArgument(0, Long.class)));
    }

    @Test
    void duplicateDeliveryCreatesOneResultAndOneReceipt() {
        AnalysisAcceptedResponse accepted = createAnalysisService.create(
                new CreateAnalysisCommand(101, "worker-key"), REVIEWER_TOKEN);
        UUID eventId = eventId(accepted.analysisId());
        AnalysisRequestedMessage message = new AnalysisRequestedMessage(
                1, eventId, accepted.analysisId(), CAPTURED_AT);

        worker.process(message, 1);
        worker.process(message, 1);

        assertThat(jdbc.queryForObject("SELECT status FROM analysis_run WHERE id = UUID_TO_BIN(?)",
                String.class, accepted.analysisId().toString())).isEqualTo("SUCCEEDED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM analysis_result WHERE analysis_id = UUID_TO_BIN(?)",
                Integer.class, accepted.analysisId().toString())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM analysis_message_receipt WHERE event_id = UUID_TO_BIN(?)",
                Integer.class, eventId.toString())).isEqualTo(1);
    }

    @Test
    void outOfOrderDeliveryAtLaterDurableStateDoesNotRegressOrRerunAnalysis() {
        AnalysisAcceptedResponse accepted = createAnalysisService.create(
                new CreateAnalysisCommand(101, "out-of-order-key"), REVIEWER_TOKEN);
        UUID laterEventId = eventId(accepted.analysisId(), "later");
        UUID earlierEventId = eventId(accepted.analysisId(), "earlier");
        AnalysisRequestedMessage laterDelivery = new AnalysisRequestedMessage(
                1, laterEventId, accepted.analysisId(), CAPTURED_AT);
        AnalysisRequestedMessage earlierDelivery = new AnalysisRequestedMessage(
                1, earlierEventId, accepted.analysisId(), CAPTURED_AT.minusSeconds(1));

        assertThat(worker.process(laterDelivery, 1))
                .isEqualTo(AnalysisWorker.WorkerOutcome.COMPLETED);
        assertThat(worker.process(earlierDelivery, 1))
                .isEqualTo(AnalysisWorker.WorkerOutcome.NOOP);

        assertThat(jdbc.queryForObject("SELECT status FROM analysis_run WHERE id = UUID_TO_BIN(?)",
                String.class, accepted.analysisId().toString())).isEqualTo("SUCCEEDED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM analysis_result WHERE analysis_id = UUID_TO_BIN(?)",
                Integer.class, accepted.analysisId().toString())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM analysis_message_receipt WHERE analysis_id = UUID_TO_BIN(?)",
                Integer.class, accepted.analysisId().toString())).isEqualTo(2);
    }

    @Test
    void deterministicSnapshotGuardrailFailureIsTerminalAndReceipted() {
        AnalysisAcceptedResponse accepted = createAnalysisService.create(
                new CreateAnalysisCommand(101, "guardrail-key"), REVIEWER_TOKEN);
        UUID eventId = eventId(accepted.analysisId());
        jdbc.update("UPDATE analysis_input_snapshot SET facts_json = CAST(? AS JSON) "
                        + "WHERE analysis_id = UUID_TO_BIN(?)",
                "{}", accepted.analysisId().toString());

        assertThat(worker.process(new AnalysisRequestedMessage(
                1, eventId, accepted.analysisId(), CAPTURED_AT), 1))
                .isEqualTo(AnalysisWorker.WorkerOutcome.FAILED_GUARDRAIL);
        assertThat(jdbc.queryForObject("SELECT status FROM analysis_run WHERE id = UUID_TO_BIN(?)",
                String.class, accepted.analysisId().toString())).isEqualTo("FAILED");
        assertThat(jdbc.queryForObject("SELECT failure_code FROM analysis_run WHERE id = UUID_TO_BIN(?)",
                String.class, accepted.analysisId().toString())).isEqualTo("FACT_VALIDATION_FAILED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM analysis_message_receipt WHERE event_id = UUID_TO_BIN(?)",
                Integer.class, eventId.toString())).isEqualTo(1);
    }

    @Test
    void crashAfterResultCommitBeforeReceiptRedeliveryDoesNotRerunAnalysis() {
        AnalysisAcceptedResponse accepted = createAnalysisService.create(
                new CreateAnalysisCommand(101, "crash-redelivery-key"), REVIEWER_TOKEN);
        UUID eventId = eventId(accepted.analysisId());
        AnalysisRequestedMessage message = new AnalysisRequestedMessage(
                1, eventId, accepted.analysisId(), CAPTURED_AT);

        RabbitTemplate rabbit = mock(RabbitTemplate.class);
        doAnswer(invocation -> {
            CorrelationData correlation = invocation.getArgument(4);
            correlation.getFuture().complete(new CorrelationData.Confirm(true, null));
            return null;
        }).when(rabbit).convertAndSend(anyString(), eq(RabbitTopologyConfiguration.ANALYSIS_ROUTING_KEY),
                any(AnalysisRequestedMessage.class), any(MessagePostProcessor.class), any(CorrelationData.class));
        AtomicInteger analysisCalls = new AtomicInteger();
        AnalysisOrchestrator countingOrchestrator = (analysisId, snapshot) -> {
            analysisCalls.incrementAndGet();
            return productionOrchestrator.analyze(analysisId, snapshot);
        };
        TransactionTemplate autocommitBoundary = mock(TransactionTemplate.class);
        doAnswer(invocation -> invocation.<org.springframework.transaction.support.TransactionCallback<?>>getArgument(0)
                .doInTransaction(null)).when(autocommitBoundary).execute(any());
        AnalysisWorker crashWorker = new AnalysisWorker(
                new CrashAfterResultRepository(runRepository),
                new SnapshotCodec(),
                countingOrchestrator,
                schemaValidator,
                factValidator,
                autocommitBoundary,
                rabbit,
                objectMapper,
                copilotProperties,
                java.time.Clock.systemUTC());

        assertThat(crashWorker.process(message, 1)).isEqualTo(AnalysisWorker.WorkerOutcome.RETRIED);
        assertThat(analysisCalls).hasValue(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM analysis_result WHERE analysis_id = UUID_TO_BIN(?)",
                Integer.class, accepted.analysisId().toString())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM analysis_message_receipt WHERE event_id = UUID_TO_BIN(?)",
                Integer.class, eventId.toString())).isZero();

        assertThat(crashWorker.process(message, 2)).isEqualTo(AnalysisWorker.WorkerOutcome.NOOP);
        assertThat(analysisCalls).hasValue(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM analysis_result WHERE analysis_id = UUID_TO_BIN(?)",
                Integer.class, accepted.analysisId().toString())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM analysis_message_receipt WHERE event_id = UUID_TO_BIN(?)",
                Integer.class, eventId.toString())).isEqualTo(1);
    }

    private static UUID eventId(UUID analysisId) {
        return eventId(analysisId, "primary");
    }

    private static UUID eventId(UUID analysisId, String deliveryOrder) {
        return UUID.nameUUIDFromBytes(("00000000-0000-0000-0000-000000000001:" + deliveryOrder + ":" + analysisId)
                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private static final class CrashAfterResultRepository implements AnalysisRunRepository {

        private final AnalysisRunRepository delegate;
        private boolean crashBeforeFirstReceipt = true;

        private CrashAfterResultRepository(AnalysisRunRepository delegate) {
            this.delegate = delegate;
        }

        @Override
        public int insert(com.finguard.copilot.analysis.domain.AnalysisRun run) {
            return delegate.insert(run);
        }

        @Override
        public java.util.Optional<AnalysisView> findByIdAndReviewer(UUID analysisId, String reviewerUsername) {
            return delegate.findByIdAndReviewer(analysisId, reviewerUsername);
        }

        @Override
        public java.util.Optional<WorkView> findForWork(UUID analysisId) {
            return delegate.findForWork(analysisId);
        }

        @Override
        public boolean hasReceipt(UUID eventId) {
            return delegate.hasReceipt(eventId);
        }

        @Override
        public int updateStatus(UUID analysisId, com.finguard.copilot.analysis.domain.AnalysisStatus expected,
                                com.finguard.copilot.analysis.domain.AnalysisStatus next,
                                String failureCode, Instant updatedAt) {
            return delegate.updateStatus(analysisId, expected, next, failureCode, updatedAt);
        }

        @Override
        public int insertResult(UUID analysisId, int schemaVersion, String recommendation,
                                String resultJson, Instant createdAt) {
            return delegate.insertResult(analysisId, schemaVersion, recommendation, resultJson, createdAt);
        }

        @Override
        public int insertReceipt(UUID eventId, UUID analysisId, Instant receivedAt) {
            if (crashBeforeFirstReceipt) {
                crashBeforeFirstReceipt = false;
                throw new IllegalStateException("simulated crash after committed result");
            }
            return delegate.insertReceipt(eventId, analysisId, receivedAt);
        }
    }

    private static ReviewTaskContext context(long taskId) {
        return new ReviewTaskContext(
                new ReviewTaskContext.ReviewTask(taskId, 0, "PENDING", "HIGH_AMOUNT",
                        "REQUIRES_REVIEW", CAPTURED_AT),
                new ReviewTaskContext.Transaction(202, 303, new BigDecimal("120000.00"), "CNY",
                        CAPTURED_AT, "CSV_IMPORT"),
                new ReviewTaskContext.Reconciliation("SUSPICIOUS", "AMOUNT_AND_TIME", "REQUIRES_REVIEW"),
                List.of(new ReviewTaskContext.RiskFact("HIGH_AMOUNT", "120000.00", "TRANSACTION")),
                new ReviewTaskContext.AccountSummary("ACTIVE", "Primary"), CAPTURED_AT);
    }
}
