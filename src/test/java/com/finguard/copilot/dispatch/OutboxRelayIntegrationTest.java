package com.finguard.copilot.dispatch;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.finguard.copilot.analysis.application.CreateAnalysisCommand;
import com.finguard.copilot.analysis.application.CreateAnalysisService;
import com.finguard.copilot.api.analysis.AnalysisAcceptedResponse;
import com.finguard.copilot.config.CopilotProperties;
import com.finguard.copilot.coreclient.port.CoreClient;
import com.finguard.copilot.coreclient.port.CurrentUser;
import com.finguard.copilot.coreclient.port.ReviewTaskContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@SpringBootTest(properties = {
        "COPILOT_DB_URL=jdbc:mysql://unused/test",
        "COPILOT_DB_USERNAME=test-only",
        "COPILOT_DB_PASSWORD=",
        "finguard.copilot.dispatch.enabled=true",
        "finguard.copilot.dispatch.relay-enabled=false",
        "finguard.copilot.dispatch.outbox-retry-initial-delay=1s",
        "spring.rabbitmq.listener.simple.auto-startup=false"
})
@Testcontainers
class OutboxRelayIntegrationTest {

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
    private OutboxRelay relay;

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private CopilotProperties copilotProperties;

    @Autowired
    private JdbcTemplate jdbc;

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
                .thenReturn(new CurrentUser("relay-reviewer", Set.of("REVIEWER")));
        when(coreClient.reviewTaskContext(anyLong(), anyString()))
                .thenAnswer(invocation -> context(invocation.getArgument(0, Long.class)));
    }

    @Test
    void confirmsBeforeMarkingPublishedAndLeavesDurableQueuedStateDuringRetryWindow() {
        AnalysisAcceptedResponse accepted = createAnalysisService.create(
                new CreateAnalysisCommand(101, "relay-key"), REVIEWER_TOKEN);

        assertThat(jdbc.queryForObject(
                "SELECT status FROM analysis_run WHERE id = UUID_TO_BIN(?)",
                String.class, accepted.analysisId().toString())).isEqualTo("CONTEXT_READY");
        assertThat(jdbc.queryForObject(
                "SELECT published_at FROM outbox_event WHERE analysis_id = UUID_TO_BIN(?)",
                Object.class, accepted.analysisId().toString())).isNull();

        relay.relayDue();

        assertThat(jdbc.queryForObject(
                "SELECT status FROM analysis_run WHERE id = UUID_TO_BIN(?)",
                String.class, accepted.analysisId().toString())).isEqualTo("QUEUED");
        assertThat(jdbc.queryForObject(
                "SELECT published_at FROM outbox_event WHERE analysis_id = UUID_TO_BIN(?)",
                Object.class, accepted.analysisId().toString())).isNotNull();
    }

    @Test
    void failedPublishIsDurableAndDueAgainAfterExplicitRetryTime() {
        AnalysisAcceptedResponse accepted = createAnalysisService.create(
                new CreateAnalysisCommand(101, "outage-key"), REVIEWER_TOKEN);
        OutboxRepository outbox = new OutboxRepository(jdbc);

        outbox.recordPublishFailure(
                accepted.analysisId(), "BROKER_UNAVAILABLE", Instant.now().plusMillis(10));

        assertThat(jdbc.queryForObject(
                "SELECT published_at FROM outbox_event WHERE analysis_id = UUID_TO_BIN(?)",
                Object.class, accepted.analysisId().toString())).isNull();
        assertThat(jdbc.queryForObject(
                "SELECT last_error_code FROM outbox_event WHERE analysis_id = UUID_TO_BIN(?)",
                String.class, accepted.analysisId().toString())).isEqualTo("BROKER_UNAVAILABLE");
        assertThat(jdbc.queryForObject(
                "SELECT next_attempt_at > CURRENT_TIMESTAMP(6) FROM outbox_event WHERE analysis_id = UUID_TO_BIN(?)",
                Boolean.class, accepted.analysisId().toString())).isTrue();
    }

    @Test
    void realRabbitOutageLeavesDueOutboxUnpublishedAndRecoveryPublishesIt() throws InterruptedException {
        AnalysisAcceptedResponse accepted = createAnalysisService.create(
                new CreateAnalysisCommand(101, "real-rabbit-outage-key"), REVIEWER_TOKEN);
        assertThat(RABBIT.isRunning()).isTrue();

        CachingConnectionFactory recoveredConnectionFactory = null;
        try {
            RABBIT.stop();
            if (rabbitTemplate.getConnectionFactory() instanceof CachingConnectionFactory connectionFactory) {
                connectionFactory.resetConnection();
            }
            assertThat(relay.relayDue()).isZero();

            Map<String, Object> failed = jdbc.queryForMap(
                    "SELECT attempt_count, published_at, last_error_code, next_attempt_at "
                            + "FROM outbox_event WHERE analysis_id = UUID_TO_BIN(?)",
                    accepted.analysisId().toString());
            assertThat(failed.get("attempt_count")).isEqualTo(1L);
            assertThat(failed.get("published_at")).isNull();
            assertThat(failed.get("last_error_code")).isEqualTo("BROKER_UNAVAILABLE");
            assertThat((LocalDateTime) failed.get("next_attempt_at"))
                    .isAfter(LocalDateTime.now());

            RABBIT.start();
            assertThat(RABBIT.isRunning()).isTrue();
            jdbc.update("UPDATE outbox_event SET next_attempt_at = CURRENT_TIMESTAMP(6) "
                    + "WHERE analysis_id = UUID_TO_BIN(?)", accepted.analysisId().toString());

            recoveredConnectionFactory = new CachingConnectionFactory(
                    RABBIT.getHost(), RABBIT.getMappedPort(5672));
            recoveredConnectionFactory.setUsername("guest");
            recoveredConnectionFactory.setPassword("guest");
            recoveredConnectionFactory.setPublisherConfirmType(CachingConnectionFactory.ConfirmType.CORRELATED);
            recoveredConnectionFactory.setPublisherReturns(true);
            RabbitAdmin recoveredAdmin = new RabbitAdmin(recoveredConnectionFactory);
            declareTopology(recoveredAdmin);
            RabbitTemplate recoveredRabbit = new RabbitTemplate(recoveredConnectionFactory);
            recoveredRabbit.setMessageConverter(new Jackson2JsonMessageConverter());
            recoveredRabbit.setMandatory(true);
            OutboxRelay recoveredRelay = new OutboxRelay(
                    new OutboxRepository(jdbc), recoveredRabbit, objectMapper, copilotProperties,
                    java.time.Clock.systemUTC());
            int processed = 0;
            Instant recoveryDeadline = Instant.now().plusSeconds(10);
            while (Instant.now().isBefore(recoveryDeadline)
                    && jdbc.queryForObject(
                    "SELECT published_at IS NULL FROM outbox_event WHERE analysis_id = UUID_TO_BIN(?)",
                    Boolean.class, accepted.analysisId().toString())) {
                processed = recoveredRelay.relayDue();
                if (processed == 1) {
                    break;
                }
                Thread.sleep(100);
            }
            assertThat(processed).isEqualTo(1);
            assertThat(jdbc.queryForObject(
                    "SELECT published_at IS NOT NULL FROM outbox_event WHERE analysis_id = UUID_TO_BIN(?)",
                    Boolean.class, accepted.analysisId().toString())).isTrue();
            assertThat(jdbc.queryForObject(
                    "SELECT status FROM analysis_run WHERE id = UUID_TO_BIN(?)",
                    String.class, accepted.analysisId().toString())).isEqualTo("QUEUED");
        } finally {
            if (recoveredConnectionFactory != null) {
                recoveredConnectionFactory.destroy();
            }
            if (!RABBIT.isRunning()) {
                RABBIT.start();
            }
        }
    }

    private static void declareTopology(RabbitAdmin admin) {
        RabbitTopologyConfiguration topology = new RabbitTopologyConfiguration();
        admin.declareExchange(topology.analysisExchange());
        admin.declareExchange(topology.retryExchange());
        admin.declareExchange(topology.deadLetterExchange());
        admin.declareQueue(topology.analysisQueue());
        admin.declareQueue(topology.retryQueue());
        admin.declareQueue(topology.deadLetterQueue());
        admin.declareBinding(topology.analysisBinding(topology.analysisQueue(), topology.analysisExchange()));
        admin.declareBinding(topology.retryBinding(topology.retryQueue(), topology.retryExchange()));
        admin.declareBinding(topology.deadLetterBinding(topology.deadLetterQueue(), topology.deadLetterExchange()));
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
