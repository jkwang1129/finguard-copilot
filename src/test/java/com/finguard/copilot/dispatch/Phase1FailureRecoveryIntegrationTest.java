package com.finguard.copilot.dispatch;

import java.sql.Timestamp;
import java.math.BigDecimal;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finguard.copilot.analysis.application.AnalysisOrchestrator;
import com.finguard.copilot.analysis.application.CreateAnalysisCommand;
import com.finguard.copilot.analysis.application.CreateAnalysisService;
import com.finguard.copilot.analysis.domain.AnalysisResult;
import com.finguard.copilot.analysis.domain.AnalysisStatus;
import com.finguard.copilot.analysis.persistence.AnalysisRunRepository;
import com.finguard.copilot.analysis.persistence.AnalysisRunRepository.AnalysisView;
import com.finguard.copilot.analysis.persistence.AnalysisRunRepository.WorkView;
import com.finguard.copilot.api.analysis.AnalysisAcceptedResponse;
import com.finguard.copilot.common.error.CopilotException;
import com.finguard.copilot.common.error.ErrorCode;
import com.finguard.copilot.config.CopilotProperties;
import com.finguard.copilot.context.SnapshotCodec;
import com.finguard.copilot.coreclient.port.CoreClient;
import com.finguard.copilot.coreclient.port.CoreClientException;
import com.finguard.copilot.coreclient.port.CurrentUser;
import com.finguard.copilot.coreclient.port.ReviewTaskContext;
import com.finguard.copilot.guardrail.AnalysisOutputSchemaValidator;
import com.finguard.copilot.guardrail.FactConsistencyValidator;
import com.rabbitmq.client.Channel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessagePostProcessor;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SpringBootTest(properties = {
        "COPILOT_DB_URL=jdbc:mysql://unused/test",
        "COPILOT_DB_USERNAME=test-only",
        "COPILOT_DB_PASSWORD=",
        "finguard.copilot.dispatch.enabled=true",
        "finguard.copilot.dispatch.relay-enabled=false",
        "finguard.copilot.dispatch.publisher-confirm-timeout=250ms",
        "spring.rabbitmq.listener.simple.auto-startup=false",
        "management.health.rabbit.enabled=false"
})
@Testcontainers
class Phase1FailureRecoveryIntegrationTest {

    private static final String REVIEWER_TOKEN =
            "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxIn0.signature";
    private static final String SNAPSHOT_SECRET = "snapshot-secret-only";
    private static final Instant NOW = Instant.parse("2026-08-26T00:00:00Z");
    private static final Instant CAPTURED_AT = Instant.parse("2026-08-25T02:03:04.123456Z");

    @Container
    @ServiceConnection
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4.10")
            .withCommand("--log-bin-trust-function-creators=1");

    @Container
    @ServiceConnection
    static final RabbitMQContainer RABBIT = new RabbitMQContainer("rabbitmq:4.0-management");

    @Autowired private CreateAnalysisService createService;
    @Autowired private AnalysisWorker worker;
    @Autowired private OutboxRepository outbox;
    @Autowired private AnalysisRunRepository runs;
    @Autowired private SnapshotCodec snapshotCodec;
    @Autowired private AnalysisOrchestrator orchestrator;
    @Autowired private AnalysisOutputSchemaValidator schemaValidator;
    @Autowired private FactConsistencyValidator factValidator;
    @Autowired private TransactionTemplate transactions;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private CopilotProperties properties;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private RabbitTemplate rabbitTemplate;
    @Autowired private ConnectionFactory connectionFactory;
    @Autowired private RabbitAdmin rabbitAdmin;

    @MockitoBean
    private CoreClient coreClient;

    @BeforeEach
    void cleanTables() {
        dropFailureTrigger();
        jdbc.update("DELETE FROM analysis_message_receipt");
        jdbc.update("DELETE FROM analysis_result");
        jdbc.update("DELETE FROM outbox_event");
        jdbc.update("DELETE FROM analysis_input_snapshot");
        jdbc.update("DELETE FROM analysis_idempotency_key");
        jdbc.update("DELETE FROM analysis_run");
        when(coreClient.currentUser(REVIEWER_TOKEN))
                .thenReturn(new CurrentUser("failure-reviewer", Set.of("REVIEWER")));
        when(coreClient.reviewTaskContext(anyLong(), anyString()))
                .thenAnswer(invocation -> context(invocation.getArgument(0, Long.class)));
    }

    @AfterEach
    void dropFailureTrigger() {
        jdbc.execute("DROP TRIGGER IF EXISTS task9_fail_result");
    }

    @Test
    void coreTimeoutLeavesNoRunSnapshotReservationOrOutboxRows() {
        when(coreClient.currentUser(REVIEWER_TOKEN))
                .thenThrow(new CoreClientException(CoreClientException.Kind.UNAVAILABLE,
                        "Core timed out", new TimeoutException("timeout")));

        assertThatThrownBy(() -> createService.create(
                new CreateAnalysisCommand(101, "core-timeout"), REVIEWER_TOKEN))
                .isInstanceOfSatisfying(CopilotException.class,
                        exception -> assertThat(exception.code()).isEqualTo(ErrorCode.SERVICE_UNAVAILABLE));
        assertThat(totalRows()).isZero();
    }

    @Test
    void mysqlTransactionFailureRollsBackRunWorkAndProducesBoundedRetry() {
        AnalysisAcceptedResponse accepted = createService.create(
                new CreateAnalysisCommand(101, "mysql-failure"), REVIEWER_TOKEN);
        jdbc.execute("""
                CREATE TRIGGER task9_fail_result BEFORE INSERT ON analysis_result
                FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'forced transaction failure'
                """);
        RabbitTemplate rabbit = confirmedRabbit();
        AnalysisWorker isolatedWorker = new AnalysisWorker(
                runs, snapshotCodec, orchestrator, schemaValidator, factValidator,
                transactions, rabbit, objectMapper, properties,
                Clock.fixed(NOW, ZoneOffset.UTC));

        assertThat(isolatedWorker.process(message(accepted.analysisId()), 1))
                .isEqualTo(AnalysisWorker.WorkerOutcome.RETRIED);
        assertThat(jdbc.queryForObject("SELECT status FROM analysis_run WHERE id = UUID_TO_BIN(?)",
                String.class, accepted.analysisId().toString())).isEqualTo("CONTEXT_READY");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM analysis_result", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM analysis_message_receipt", Integer.class)).isZero();
    }

    @Test
    void rabbitUnavailableThenRestoredKeepsOneOutboxRowAndPublishesExactlyOnce() throws Exception {
        AnalysisAcceptedResponse accepted = createService.create(
                new CreateAnalysisCommand(101, "rabbit-recovery"), REVIEWER_TOKEN);
        OutboxRelay isolatedRelay = new OutboxRelay(outbox, rabbitTemplate, objectMapper, properties,
                Clock.fixed(NOW, ZoneOffset.UTC));

        jdbc.update("UPDATE outbox_event SET next_attempt_at = '2000-01-01 00:00:00.000000' "
                        + "WHERE analysis_id = UUID_TO_BIN(?)",
                accepted.analysisId().toString());
        try {
            RABBIT.stop();
            assertThat(isolatedRelay.relayDue()).isZero();
            assertThat(jdbc.queryForObject("SELECT attempt_count FROM outbox_event WHERE analysis_id = UUID_TO_BIN(?)",
                    Integer.class, accepted.analysisId().toString())).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT next_attempt_at FROM outbox_event WHERE analysis_id = UUID_TO_BIN(?)",
                    Timestamp.class, accepted.analysisId().toString()).toInstant()).isAfter(NOW);
            assertThat(jdbc.queryForObject("SELECT published_at FROM outbox_event WHERE analysis_id = UUID_TO_BIN(?)",
                    Timestamp.class, accepted.analysisId().toString())).isNull();

            RABBIT.start();
            reconnectAndInitializeRabbit();
            jdbc.update("UPDATE outbox_event SET next_attempt_at = '2000-01-01 00:00:00.000000' "
                            + "WHERE analysis_id = UUID_TO_BIN(?)",
                    accepted.analysisId().toString());
            assertThat(isolatedRelay.relayDue()).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM outbox_event WHERE published_at IS NOT NULL",
                    Integer.class)).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM analysis_run WHERE id = UUID_TO_BIN(?)",
                    Integer.class, accepted.analysisId().toString())).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT status FROM analysis_run WHERE id = UUID_TO_BIN(?)",
                    String.class, accepted.analysisId().toString())).isEqualTo("QUEUED");
            assertThat(rabbitTemplate.receiveAndConvert(RabbitTopologyConfiguration.ANALYSIS_QUEUE, 2_000))
                    .isInstanceOf(AnalysisRequestedMessage.class);
        } finally {
            if (!RABBIT.isRunning()) {
                RABBIT.start();
                reconnectAndInitializeRabbit();
            }
        }
    }

    @Test
    void duplicateAndOutOfOrderMessagesLeaveOneResultAndReceiptsWithoutRegression() {
        AnalysisAcceptedResponse accepted = createService.create(
                new CreateAnalysisCommand(101, "duplicate-order"), REVIEWER_TOKEN);
        UUID laterId = UUID.nameUUIDFromBytes(("later:" + accepted.analysisId()).getBytes());
        UUID earlierId = UUID.nameUUIDFromBytes(("earlier:" + accepted.analysisId()).getBytes());

        assertThat(worker.process(new AnalysisRequestedMessage(1, laterId, accepted.analysisId(), CAPTURED_AT), 1))
                .isEqualTo(AnalysisWorker.WorkerOutcome.COMPLETED);
        assertThat(worker.process(new AnalysisRequestedMessage(1, earlierId, accepted.analysisId(),
                CAPTURED_AT.minusSeconds(1)), 1)).isEqualTo(AnalysisWorker.WorkerOutcome.NOOP);
        assertThat(worker.process(new AnalysisRequestedMessage(1, laterId, accepted.analysisId(), CAPTURED_AT), 1))
                .isEqualTo(AnalysisWorker.WorkerOutcome.NOOP);
        assertThat(jdbc.queryForObject("SELECT status FROM analysis_run WHERE id = UUID_TO_BIN(?)",
                String.class, accepted.analysisId().toString())).isEqualTo("SUCCEEDED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM analysis_result", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM analysis_message_receipt", Integer.class)).isEqualTo(2);
    }

    @Test
    void malformedMessageIsDlqBoundedAndDoesNotChangeRowsOrLogSecrets() throws Exception {
        RabbitTemplate rabbit = confirmedRabbit();
        Channel channel = mock(Channel.class);
        MessageProperties messageProperties = new MessageProperties();
        messageProperties.setDeliveryTag(9L);
        ch.qos.logback.classic.Logger logger = (ch.qos.logback.classic.Logger)
                LoggerFactory.getLogger(AnalysisWorker.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            AnalysisWorker isolatedWorker = new AnalysisWorker(
                    runs, snapshotCodec, orchestrator, schemaValidator, factValidator,
                    transactions, rabbit, objectMapper, properties,
                    Clock.fixed(NOW, ZoneOffset.UTC));
            isolatedWorker.onMessage(new Message(("malformed:" + SNAPSHOT_SECRET).getBytes(), messageProperties), channel);
            verify(channel).basicAck(9L, false);
            verify(channel, never()).basicNack(anyLong(), eq(false), eq(false));
            verify(rabbit).send(eq(RabbitTopologyConfiguration.DLX_EXCHANGE),
                    eq(RabbitTopologyConfiguration.ANALYSIS_ROUTING_KEY), any(Message.class), any(CorrelationData.class));
            assertThat(totalRows()).isZero();
            assertThat(appender.list).allSatisfy(event -> assertThat(event.getFormattedMessage())
                    .doesNotContain(REVIEWER_TOKEN, SNAPSHOT_SECRET));
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    @Test
    void workerRestartAfterCommittedResultRollbackDoesNotRerunAnalysisOrDuplicateRows() throws Exception {
        AnalysisAcceptedResponse accepted = createService.create(
                new CreateAnalysisCommand(101, "worker-restart"), REVIEWER_TOKEN);
        UUID eventId = UUID.nameUUIDFromBytes(("restart:" + accepted.analysisId()).getBytes());
        AnalysisRequestedMessage message = message(accepted.analysisId(), eventId);
        AtomicInteger calls = new AtomicInteger();
        AnalysisOrchestrator counting = (analysisId, snapshot) -> {
            calls.incrementAndGet();
            return orchestrator.analyze(analysisId, snapshot);
        };
        AnalysisResult committed = orchestrator.analyze(
                accepted.analysisId(), snapshotCodec.capture(context(101)));
        String committedJson = objectMapper.writeValueAsString(committed);
        transactions.executeWithoutResult(status -> {
            assertThat(runs.updateStatus(accepted.analysisId(), AnalysisStatus.CONTEXT_READY,
                    AnalysisStatus.QUEUED, null, NOW)).isEqualTo(1);
            assertThat(runs.updateStatus(accepted.analysisId(), AnalysisStatus.QUEUED,
                    AnalysisStatus.RETRIEVAL_READY, null, NOW)).isEqualTo(1);
            assertThat(runs.updateStatus(accepted.analysisId(), AnalysisStatus.RETRIEVAL_READY,
                    AnalysisStatus.GENERATING, null, NOW)).isEqualTo(1);
            assertThat(runs.updateStatus(accepted.analysisId(), AnalysisStatus.GENERATING,
                    AnalysisStatus.VALIDATING, null, NOW)).isEqualTo(1);
            assertThat(runs.insertResult(accepted.analysisId(), committed.schemaVersion(),
                    committed.recommendation().name(), committedJson, NOW)).isEqualTo(1);
        });
        AnalysisWorker crashed = new AnalysisWorker(
                new CrashBeforeReceiptRepository(runs), snapshotCodec, counting,
                schemaValidator, factValidator, transactions, confirmedRabbit(), objectMapper,
                properties, Clock.fixed(NOW, ZoneOffset.UTC));

        assertThat(crashed.process(message, 1)).isEqualTo(AnalysisWorker.WorkerOutcome.RETRIED);
        assertThat(calls).hasValue(0);
        assertThat(jdbc.queryForObject("SELECT status FROM analysis_run WHERE id = UUID_TO_BIN(?)",
                String.class, accepted.analysisId().toString())).isEqualTo("VALIDATING");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM analysis_result", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM analysis_message_receipt", Integer.class)).isZero();

        assertThat(crashed.process(message, 2))
                .isEqualTo(AnalysisWorker.WorkerOutcome.COMPLETED_FROM_STORED_RESULT);
        assertThat(calls).hasValue(0);
        assertThat(jdbc.queryForObject("SELECT status FROM analysis_run WHERE id = UUID_TO_BIN(?)",
                String.class, accepted.analysisId().toString())).isEqualTo("SUCCEEDED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM analysis_result", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM analysis_message_receipt", Integer.class)).isEqualTo(1);
    }

    private void resetRabbitConnection() {
        if (connectionFactory instanceof CachingConnectionFactory caching) {
            caching.setAddresses(RABBIT.getHost() + ":" + RABBIT.getAmqpPort());
            caching.resetConnection();
        }
    }

    private void reconnectAndInitializeRabbit() throws InterruptedException {
        RuntimeException lastFailure = null;
        for (int attempt = 0; attempt < 50; attempt++) {
            resetRabbitConnection();
            try {
                rabbitAdmin.initialize();
                return;
            } catch (RuntimeException exception) {
                lastFailure = exception;
                Thread.sleep(200);
            }
        }
        throw lastFailure;
    }

    private RabbitTemplate confirmedRabbit() {
        RabbitTemplate rabbit = mock(RabbitTemplate.class);
        doAnswer(invocation -> {
            CorrelationData correlation = invocation.getArgument(4);
            correlation.getFuture().complete(new CorrelationData.Confirm(true, null));
            return null;
        }).when(rabbit).convertAndSend(anyString(), eq(RabbitTopologyConfiguration.ANALYSIS_ROUTING_KEY),
                any(AnalysisRequestedMessage.class), any(MessagePostProcessor.class), any(CorrelationData.class));
        doAnswer(invocation -> {
            CorrelationData correlation = invocation.getArgument(3);
            correlation.getFuture().complete(new CorrelationData.Confirm(true, null));
            return null;
        }).when(rabbit).send(anyString(), eq(RabbitTopologyConfiguration.ANALYSIS_ROUTING_KEY),
                any(Message.class), any(CorrelationData.class));
        return rabbit;
    }

    private AnalysisRequestedMessage message(UUID analysisId) {
        return message(analysisId, UUID.nameUUIDFromBytes(("event:" + analysisId).getBytes()));
    }

    private AnalysisRequestedMessage message(UUID analysisId, UUID eventId) {
        return new AnalysisRequestedMessage(1, eventId, analysisId, CAPTURED_AT);
    }

    private int totalRows() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM analysis_run", Integer.class)
                + jdbc.queryForObject("SELECT COUNT(*) FROM analysis_idempotency_key", Integer.class)
                + jdbc.queryForObject("SELECT COUNT(*) FROM analysis_input_snapshot", Integer.class)
                + jdbc.queryForObject("SELECT COUNT(*) FROM outbox_event", Integer.class);
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

    private static final class CrashBeforeReceiptRepository implements AnalysisRunRepository {
        private final AnalysisRunRepository delegate;
        private boolean first = true;

        private CrashBeforeReceiptRepository(AnalysisRunRepository delegate) {
            this.delegate = delegate;
        }

        public int insert(com.finguard.copilot.analysis.domain.AnalysisRun run) { return delegate.insert(run); }
        public java.util.Optional<AnalysisView> findByIdAndReviewer(UUID id, String reviewer) {
            return delegate.findByIdAndReviewer(id, reviewer);
        }
        public java.util.Optional<WorkView> findForWork(UUID id) { return delegate.findForWork(id); }
        public boolean hasReceipt(UUID id) { return delegate.hasReceipt(id); }
        public int updateStatus(UUID id, AnalysisStatus expected, AnalysisStatus next, String code, Instant at) {
            return delegate.updateStatus(id, expected, next, code, at);
        }
        public int insertResult(UUID id, int schema, String recommendation, String json, Instant at) {
            return delegate.insertResult(id, schema, recommendation, json, at);
        }
        public int insertReceipt(UUID eventId, UUID analysisId, Instant at) {
            if (first) {
                first = false;
                throw new IllegalStateException("simulated worker restart");
            }
            return delegate.insertReceipt(eventId, analysisId, at);
        }
    }
}
