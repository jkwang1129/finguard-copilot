package com.finguard.copilot.dispatch;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.finguard.copilot.analysis.application.AnalysisOrchestrator;
import com.finguard.copilot.analysis.application.AnalysisExecution;
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
import com.finguard.copilot.model.port.ModelUsage;
import com.finguard.copilot.usage.ModelCallReservations;
import com.finguard.copilot.usage.ModelUsageRecorder;
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

    @Autowired
    private TransactionTemplate transactions;

    @Autowired
    private ModelUsageRecorder usageRecorder;

    @Autowired
    private ModelCallReservations reservations;

    @MockitoBean
    private CoreClient coreClient;

    @BeforeEach
    void cleanTables() {
        jdbc.update("DELETE FROM model_usage_ledger");
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

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {true, false})
    void modelCallIsNotRepeatedWhenResultOrReceiptWriteRollsBack(boolean failResultWrite) {
        AnalysisAcceptedResponse accepted = createAnalysisService.create(
                new CreateAnalysisCommand(101, "model-write-failure-" + failResultWrite), REVIEWER_TOKEN);
        UUID analysisId = accepted.analysisId();
        UUID eventId = eventId(analysisId);
        AnalysisRequestedMessage message = new AnalysisRequestedMessage(1, eventId, analysisId, CAPTURED_AT);
        AtomicInteger modelCalls = new AtomicInteger();
        AnalysisOrchestrator model = new AnalysisOrchestrator() {
            @Override
            public boolean requiresModelCall() {
                return true;
            }

            @Override
            public com.finguard.copilot.analysis.domain.AnalysisResult analyze(
                    UUID id, com.finguard.copilot.context.FactSnapshot snapshot) {
                return productionOrchestrator.analyze(id, snapshot);
            }

            @Override
            public AnalysisExecution execute(UUID id, com.finguard.copilot.context.FactSnapshot snapshot) {
                modelCalls.incrementAndGet();
                return new AnalysisExecution(analyze(id, snapshot), "qwen", "model-v1", "prompt-v1",
                        new ModelUsage(12, 4, java.time.Duration.ofMillis(42)));
            }
        };
        AnalysisRunRepository faultyRuns = new FaultyWriteRepository(runRepository, failResultWrite);
        RabbitTemplate rabbit = mock(RabbitTemplate.class);
        doAnswer(invocation -> {
            CorrelationData correlation = invocation.getArgument(4);
            correlation.getFuture().complete(new CorrelationData.Confirm(true, null));
            return null;
        }).when(rabbit).convertAndSend(anyString(), eq(RabbitTopologyConfiguration.ANALYSIS_ROUTING_KEY),
                any(AnalysisRequestedMessage.class), any(MessagePostProcessor.class), any(CorrelationData.class));
        AnalysisWorker protectedWorker = new AnalysisWorker(faultyRuns, new SnapshotCodec(), model,
                schemaValidator, factValidator, transactions, rabbit, objectMapper, copilotProperties,
                java.time.Clock.systemUTC(), usageRecorder, reservations);

        assertThat(protectedWorker.process(message, 1)).isEqualTo(AnalysisWorker.WorkerOutcome.DEAD_LETTERED);
        assertThat(protectedWorker.process(message, 2)).isEqualTo(AnalysisWorker.WorkerOutcome.NOOP);
        assertThat(modelCalls).hasValue(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM model_call_reservation WHERE analysis_id = UUID_TO_BIN(?)",
                Integer.class, analysisId.toString())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM model_usage_ledger WHERE analysis_id = UUID_TO_BIN(?)",
                Integer.class, analysisId.toString())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM analysis_result WHERE analysis_id = UUID_TO_BIN(?)",
                Integer.class, analysisId.toString())).isZero();
        assertThat(jdbc.queryForObject("SELECT failure_code FROM analysis_run WHERE id = UUID_TO_BIN(?)",
                String.class, analysisId.toString())).isEqualTo("MODEL_CALL_OUTCOME_UNKNOWN");
    }

    private static final class FaultyWriteRepository implements AnalysisRunRepository {
        private final AnalysisRunRepository delegate;
        private final boolean failResultWrite;
        private boolean failed;

        private FaultyWriteRepository(AnalysisRunRepository delegate, boolean failResultWrite) {
            this.delegate = delegate;
            this.failResultWrite = failResultWrite;
        }

        @Override
        public int insert(com.finguard.copilot.analysis.domain.AnalysisRun run) {
            return delegate.insert(run);
        }

        @Override
        public java.util.Optional<AnalysisView> findByIdAndReviewer(UUID id, String reviewer) {
            return delegate.findByIdAndReviewer(id, reviewer);
        }

        @Override
        public java.util.Optional<WorkView> findForWork(UUID id) {
            return delegate.findForWork(id);
        }

        @Override
        public boolean hasReceipt(UUID eventId) {
            return delegate.hasReceipt(eventId);
        }

        @Override
        public int updateStatus(UUID id, com.finguard.copilot.analysis.domain.AnalysisStatus expected,
                                com.finguard.copilot.analysis.domain.AnalysisStatus next,
                                String code, Instant at) {
            return delegate.updateStatus(id, expected, next, code, at);
        }

        @Override
        public int insertResult(UUID id, int version, String recommendation, String json, Instant at) {
            if (failResultWrite && !failed) {
                failed = true;
                throw new IllegalStateException("result write failed");
            }
            return delegate.insertResult(id, version, recommendation, json, at);
        }

        @Override
        public int insertReceipt(UUID eventId, UUID id, Instant at) {
            if (!failResultWrite && !failed) {
                failed = true;
                throw new IllegalStateException("receipt write failed");
            }
            return delegate.insertReceipt(eventId, id, at);
        }
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

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"false,false","true,false","false,true","true,true"})
    void storedRecoveryAuthorizesDirectionAndPreservesRefusal(boolean modelPath,boolean refusal) throws Exception {
        var accepted=createAnalysisService.create(new CreateAnalysisCommand(101,"stored-"+modelPath+"-"+refusal),REVIEWER_TOKEN);
        var id=accepted.analysisId();
        var conservative=productionOrchestrator.analyze(id,new SnapshotCodec().capture(context(101)));
        var unsafe=new com.finguard.copilot.analysis.domain.AnalysisResult(1,id,conservative.reviewTaskSummary(),
                conservative.riskRuleExplanation(),conservative.businessEvidence(),refusal?List.of():List.of(
                new com.finguard.copilot.analysis.domain.AnalysisResult.PolicyCitation("old-unreviewed","v1","Document","Synthetic old unapproved policy")),
                refusal?com.finguard.copilot.analysis.domain.AnalysisRecommendation.INSUFFICIENT_EVIDENCE:
                        com.finguard.copilot.analysis.domain.AnalysisRecommendation.CONFIRMED,"fixture",List.of(),List.of());
        runRepository.insertResult(id,1,unsafe.recommendation().name(),objectMapper.writeValueAsString(unsafe),CAPTURED_AT);
        var noCall=new AnalysisOrchestrator(){
            public boolean requiresModelCall(){return modelPath;}
            public com.finguard.copilot.analysis.domain.AnalysisResult analyze(UUID ignored,com.finguard.copilot.context.FactSnapshot snapshot){
                throw new AssertionError("Stored-result recovery must never invoke a model");
            }
        };
        var rabbit=mock(RabbitTemplate.class);
        doAnswer(invocation->{invocation.<CorrelationData>getArgument(4).getFuture().complete(new CorrelationData.Confirm(true,null));return null;})
                .when(rabbit).convertAndSend(anyString(),anyString(),any(AnalysisRequestedMessage.class),any(MessagePostProcessor.class),any(CorrelationData.class));
        var guarded=new AnalysisWorker(runRepository,new SnapshotCodec(),noCall,schemaValidator,factValidator,transactions,
                rabbit,objectMapper,copilotProperties,java.time.Clock.systemUTC(),usageRecorder,reservations);
        var outcome=guarded.process(new AnalysisRequestedMessage(1,eventId(id),id,CAPTURED_AT),1);
        assertThat(runRepository.findByIdAndReviewer(id,"worker-reviewer").orElseThrow().status())
                .isEqualTo(refusal?com.finguard.copilot.analysis.domain.AnalysisStatus.SUCCEEDED:
                        com.finguard.copilot.analysis.domain.AnalysisStatus.FAILED);
        if(refusal) assertThat(outcome).isEqualTo(AnalysisWorker.WorkerOutcome.COMPLETED_FROM_STORED_RESULT);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM model_call_reservation WHERE analysis_id=UUID_TO_BIN(?)",Integer.class,id.toString())).isZero();
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
