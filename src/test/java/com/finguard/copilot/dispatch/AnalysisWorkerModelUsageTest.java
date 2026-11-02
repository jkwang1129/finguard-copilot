package com.finguard.copilot.dispatch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.times;

import java.math.BigDecimal;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.finguard.copilot.analysis.application.AnalysisExecution;
import com.finguard.copilot.analysis.application.AnalysisOrchestrator;
import com.finguard.copilot.analysis.application.ModelInvocationException;
import com.finguard.copilot.analysis.domain.AnalysisRecommendation;
import com.finguard.copilot.analysis.domain.AnalysisResult;
import com.finguard.copilot.analysis.domain.AnalysisStatus;
import com.finguard.copilot.analysis.persistence.AnalysisRunRepository;
import com.finguard.copilot.config.CopilotProperties;
import com.finguard.copilot.common.error.ErrorCode;
import com.finguard.copilot.context.FactSnapshot;
import com.finguard.copilot.context.SnapshotCodec;
import com.finguard.copilot.coreclient.port.ReviewTaskContext;
import com.finguard.copilot.guardrail.AnalysisOutputSchemaValidator;
import com.finguard.copilot.guardrail.FactConsistencyValidator;
import com.finguard.copilot.model.port.ModelUsage;
import com.finguard.copilot.usage.ModelUsageRecorder;
import com.finguard.copilot.usage.ModelCallReservations;
import org.springframework.amqp.core.MessagePostProcessor;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import com.rabbitmq.client.Channel;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.transaction.support.TransactionTemplate;

class AnalysisWorkerModelUsageTest {

    @Test void reservedCallRecoveryHasAnAttemptBoundEvenIfClockStalls() {
        var codec=new SnapshotCodec();var snapshot=codec.capture(facts());
        var runs=mock(AnalysisRunRepository.class);
        when(runs.findForWork(ANALYSIS_ID)).thenReturn(Optional.of(new AnalysisRunRepository.WorkView(
                ANALYSIS_ID,AnalysisStatus.CONTEXT_READY,null,new String(codec.canonicalBytes(snapshot),StandardCharsets.UTF_8),snapshot.sha256(),null)));
        when(runs.updateStatus(any(),any(),any(),any(),any())).thenReturn(1);
        var model=mock(AnalysisOrchestrator.class);when(model.requiresModelCall()).thenReturn(true);when(model.managesModelReservation()).thenReturn(true);
        var reservations=mock(ModelCallReservations.class);when(reservations.reservedAt(ANALYSIS_ID)).thenReturn(Optional.of(CAPTURED_AT));
        var transactions=mock(TransactionTemplate.class);
        doAnswer(invocation->invocation.<org.springframework.transaction.support.TransactionCallback<?>>getArgument(0).doInTransaction(null)).when(transactions).execute(any());
        doAnswer(invocation->{invocation.<java.util.function.Consumer<org.springframework.transaction.TransactionStatus>>getArgument(0).accept(null);return null;})
                .when(transactions).executeWithoutResult(any());
        var worker=new AnalysisWorker(runs,codec,model,new AnalysisOutputSchemaValidator(),new FactConsistencyValidator(),transactions,
                confirmedRabbit(),new ObjectMapper(),properties(),Clock.fixed(CAPTURED_AT,ZoneOffset.UTC),mock(ModelUsageRecorder.class),reservations);
        assertThat(worker.process(new AnalysisRequestedMessage(1,EVENT_ID,ANALYSIS_ID,CAPTURED_AT),Integer.MAX_VALUE))
                .isEqualTo(AnalysisWorker.WorkerOutcome.DEAD_LETTERED);
        verify(model,never()).execute(any(),any());
    }

    @Test
    void capacityLossAfterCommittedClaimIsTerminalWithoutAnotherRetry() {
        var codec=new SnapshotCodec();var snapshot=codec.capture(facts());
        var runs=mock(AnalysisRunRepository.class);
        when(runs.findForWork(ANALYSIS_ID)).thenReturn(Optional.of(new AnalysisRunRepository.WorkView(
                ANALYSIS_ID,AnalysisStatus.CONTEXT_READY,null,new String(codec.canonicalBytes(snapshot),StandardCharsets.UTF_8),snapshot.sha256(),null)));
        when(runs.updateStatus(any(),any(),any(),any(),any())).thenReturn(1);
        var model=mock(AnalysisOrchestrator.class);when(model.requiresModelCall()).thenReturn(true);when(model.managesModelReservation()).thenReturn(true);
        when(model.execute(eq(ANALYSIS_ID),any())).thenThrow(new com.finguard.copilot.governance.ClaimedCallNotSentException(ErrorCode.MODEL_CAPACITY_UNAVAILABLE));
        var reservations=mock(ModelCallReservations.class);
        when(reservations.reservedAt(ANALYSIS_ID)).thenReturn(Optional.empty(),Optional.of(CAPTURED_AT));
        var transactions=mock(TransactionTemplate.class);
        doAnswer(invocation->invocation.<org.springframework.transaction.support.TransactionCallback<?>>getArgument(0).doInTransaction(null)).when(transactions).execute(any());
        doAnswer(invocation->{invocation.<java.util.function.Consumer<org.springframework.transaction.TransactionStatus>>getArgument(0).accept(null);return null;})
                .when(transactions).executeWithoutResult(any());
        var recorder=mock(ModelUsageRecorder.class);var rabbit=confirmedRabbit();
        var worker=new AnalysisWorker(runs,codec,model,new AnalysisOutputSchemaValidator(),new FactConsistencyValidator(),transactions,
                rabbit,new ObjectMapper(),properties(),Clock.fixed(CAPTURED_AT,ZoneOffset.UTC),recorder,reservations);
        assertThat(worker.process(new AnalysisRequestedMessage(1,EVENT_ID,ANALYSIS_ID,CAPTURED_AT),1)).isEqualTo(AnalysisWorker.WorkerOutcome.DEAD_LETTERED);
        org.mockito.Mockito.verifyNoInteractions(recorder);
        verify(rabbit,never()).convertAndSend(eq(RabbitTopologyConfiguration.RETRY_EXCHANGE),anyString(),any(),any(MessagePostProcessor.class),any(CorrelationData.class));
    }

    @Test
    void numericGuardRejectionRecordsUsageAndNeverStoresResult() {
        SnapshotCodec codec = new SnapshotCodec();
        FactSnapshot snapshot = codec.capture(facts());
        AnalysisRunRepository runs = mock(AnalysisRunRepository.class);
        when(runs.findForWork(ANALYSIS_ID)).thenReturn(Optional.of(new AnalysisRunRepository.WorkView(
                ANALYSIS_ID, AnalysisStatus.CONTEXT_READY, null,
                new String(codec.canonicalBytes(snapshot), StandardCharsets.UTF_8), snapshot.sha256(), null)));
        when(runs.updateStatus(any(), any(), any(), any(), any())).thenReturn(1);
        AnalysisOrchestrator model = mock(AnalysisOrchestrator.class);
        when(model.requiresModelCall()).thenReturn(true);
        ModelUsage usage = new ModelUsage(120, 25, Duration.ofMillis(42));
        when(model.execute(eq(ANALYSIS_ID), any(FactSnapshot.class)))
                .thenThrow(new ModelInvocationException(ErrorCode.FACT_VALIDATION_FAILED, "qwen",
                        "model-v1", "prompt-v1", usage, "FAILURE"));
        ModelCallReservations reservations = mock(ModelCallReservations.class);
        when(reservations.reserve(eq(ANALYSIS_ID), any())).thenReturn(true);
        ModelUsageRecorder recorder = mock(ModelUsageRecorder.class);
        TransactionTemplate transactions = mock(TransactionTemplate.class);
        doAnswer(invocation -> invocation.<org.springframework.transaction.support.TransactionCallback<?>>getArgument(0)
                .doInTransaction(null)).when(transactions).execute(any());
        doAnswer(invocation -> {
            invocation.<java.util.function.Consumer<org.springframework.transaction.TransactionStatus>>getArgument(0)
                    .accept(null);
            return null;
        }).when(transactions).executeWithoutResult(any());
        AnalysisWorker worker = new AnalysisWorker(runs, codec, model, new AnalysisOutputSchemaValidator(),
                new FactConsistencyValidator(), transactions, confirmedRabbit(), new ObjectMapper(), properties(),
                Clock.fixed(CAPTURED_AT, ZoneOffset.UTC), recorder, reservations);

        assertThat(worker.process(new AnalysisRequestedMessage(1, EVENT_ID, ANALYSIS_ID, CAPTURED_AT), 1))
                .isEqualTo(AnalysisWorker.WorkerOutcome.DEAD_LETTERED);
        verify(recorder).record(ANALYSIS_ID, "qwen", "model-v1", "prompt-v1", usage, "FAILURE");
        verify(runs).updateStatus(ANALYSIS_ID, AnalysisStatus.CONTEXT_READY, AnalysisStatus.FAILED,
                "FACT_VALIDATION_FAILED", CAPTURED_AT);
        verify(runs).insertReceipt(EVENT_ID, ANALYSIS_ID, CAPTURED_AT);
        verify(runs, never()).insertResult(any(), any(Integer.class), anyString(), anyString(), any());
    }

    @Test
    void committedReservationPreventsSecondPaidCallAfterResultWriteFails() {
        SnapshotCodec codec = new SnapshotCodec();
        FactSnapshot snapshot = codec.capture(facts());
        AnalysisRunRepository runs = mock(AnalysisRunRepository.class);
        when(runs.findForWork(ANALYSIS_ID)).thenReturn(Optional.of(new AnalysisRunRepository.WorkView(
                ANALYSIS_ID, AnalysisStatus.CONTEXT_READY, null,
                new String(codec.canonicalBytes(snapshot), StandardCharsets.UTF_8), snapshot.sha256(), null)));
        when(runs.updateStatus(any(), any(), any(), any(), any())).thenReturn(1);
        when(runs.insertResult(any(), any(Integer.class), anyString(), anyString(), any()))
                .thenThrow(new IllegalStateException("result write failed"));
        AnalysisOrchestrator model = mock(AnalysisOrchestrator.class);
        when(model.requiresModelCall()).thenReturn(true);
        ModelUsage usage = new ModelUsage(120, 25, Duration.ofMillis(42));
        when(model.execute(eq(ANALYSIS_ID), any(FactSnapshot.class)))
                .thenReturn(new AnalysisExecution(result(true), "qwen", "model-v1", "prompt-v1", usage));
        ModelCallReservations reservations = mock(ModelCallReservations.class);
        when(reservations.reserve(eq(ANALYSIS_ID), any())).thenReturn(true, false);
        when(reservations.reservedAt(ANALYSIS_ID)).thenReturn(Optional.of(CAPTURED_AT.minusSeconds(300)));
        TransactionTemplate transactions = mock(TransactionTemplate.class);
        doAnswer(invocation -> invocation.<org.springframework.transaction.support.TransactionCallback<?>>getArgument(0)
                .doInTransaction(null)).when(transactions).execute(any());
        doAnswer(invocation -> {
            invocation.<java.util.function.Consumer<org.springframework.transaction.TransactionStatus>>getArgument(0)
                    .accept(null);
            return null;
        }).when(transactions).executeWithoutResult(any());
        RabbitTemplate rabbit = confirmedRabbit();
        AnalysisWorker worker = new AnalysisWorker(runs, codec, model, new AnalysisOutputSchemaValidator(),
                new FactConsistencyValidator(), transactions, rabbit, new ObjectMapper(), properties(),
                Clock.fixed(CAPTURED_AT, ZoneOffset.UTC), mock(ModelUsageRecorder.class), reservations);
        AnalysisRequestedMessage message = new AnalysisRequestedMessage(1, EVENT_ID, ANALYSIS_ID, CAPTURED_AT);

        worker.process(message, 1);
        worker.process(message, 2);

        verify(model, times(1)).execute(eq(ANALYSIS_ID), any(FactSnapshot.class));
    }

    @Test
    void failureLedgerOutageDoesNotEscapeWorkerRecovery() throws Exception {
        SnapshotCodec codec = new SnapshotCodec();
        FactSnapshot snapshot = codec.capture(facts());
        AnalysisRunRepository runs = mock(AnalysisRunRepository.class);
        when(runs.findForWork(ANALYSIS_ID)).thenReturn(Optional.of(new AnalysisRunRepository.WorkView(
                ANALYSIS_ID, AnalysisStatus.CONTEXT_READY, null,
                new String(codec.canonicalBytes(snapshot), StandardCharsets.UTF_8), snapshot.sha256(), null)));
        when(runs.updateStatus(any(), any(), any(), any(), any())).thenReturn(1);
        AnalysisOrchestrator model = mock(AnalysisOrchestrator.class);
        when(model.requiresModelCall()).thenReturn(true);
        when(model.execute(eq(ANALYSIS_ID), any(FactSnapshot.class)))
                .thenThrow(new ModelInvocationException(ErrorCode.MODEL_TIMEOUT, "qwen", "model-v1", "prompt-v1",
                        new ModelUsage(0, 0, Duration.ofMillis(42)), "TIMEOUT"));
        ModelCallReservations reservations = mock(ModelCallReservations.class);
        when(reservations.reserve(eq(ANALYSIS_ID), any())).thenReturn(true, false);
        when(reservations.reservedAt(ANALYSIS_ID)).thenReturn(Optional.of(CAPTURED_AT.minusSeconds(300)));
        ModelUsageRecorder recorder = mock(ModelUsageRecorder.class);
        org.mockito.Mockito.doThrow(new IllegalStateException("ledger unavailable")).when(recorder)
                .record(any(), anyString(), anyString(), anyString(), any(), anyString());
        TransactionTemplate transactions = mock(TransactionTemplate.class);
        doAnswer(invocation -> invocation.<org.springframework.transaction.support.TransactionCallback<?>>getArgument(0)
                .doInTransaction(null)).when(transactions).execute(any());
        doAnswer(invocation -> {
            invocation.<java.util.function.Consumer<org.springframework.transaction.TransactionStatus>>getArgument(0)
                    .accept(null);
            return null;
        }).when(transactions).executeWithoutResult(any());
        ObjectMapper mapper = new ObjectMapper().registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());
        AnalysisWorker worker = new AnalysisWorker(runs, codec, model, new AnalysisOutputSchemaValidator(),
                new FactConsistencyValidator(), transactions, confirmedRabbit(), mapper, properties(),
                Clock.fixed(CAPTURED_AT, ZoneOffset.UTC), recorder, reservations);

        assertThat(worker.process(new AnalysisRequestedMessage(1, EVENT_ID, ANALYSIS_ID, CAPTURED_AT), 1))
                .isEqualTo(AnalysisWorker.WorkerOutcome.DEAD_LETTERED);

        org.mockito.Mockito.doThrow(new IllegalStateException("database unavailable"))
                .when(transactions).executeWithoutResult(any());
        MessageProperties messageProperties = new MessageProperties();
        messageProperties.setDeliveryTag(55L);
        Channel channel = mock(Channel.class);
        worker.onMessage(new Message(mapper.writeValueAsBytes(
                new AnalysisRequestedMessage(1, EVENT_ID, ANALYSIS_ID, CAPTURED_AT)), messageProperties), channel);
        verify(channel).basicNack(55L, false, true);
        verify(model, times(1)).execute(eq(ANALYSIS_ID), any(FactSnapshot.class));
    }

    private RabbitTemplate confirmedRabbit() {
        RabbitTemplate rabbit = mock(RabbitTemplate.class);
        doAnswer(invocation -> {
            CorrelationData correlation = invocation.getArgument(4);
            correlation.getFuture().complete(new CorrelationData.Confirm(true, null));
            return null;
        }).when(rabbit).convertAndSend(anyString(), eq(RabbitTopologyConfiguration.ANALYSIS_ROUTING_KEY),
                any(AnalysisRequestedMessage.class), any(MessagePostProcessor.class), any(CorrelationData.class));
        return rabbit;
    }

    private static final UUID ANALYSIS_ID = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final UUID EVENT_ID = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
    private static final Instant CAPTURED_AT = Instant.parse("2026-08-31T06:00:00Z");

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {true, false})
    void recordsModelUsageEvenWhenBusinessValidationRejectsTheResponse(boolean validFacts) {
        SnapshotCodec snapshotCodec = new SnapshotCodec();
        FactSnapshot snapshot = snapshotCodec.capture(facts());
        AnalysisRunRepository runs = mock(AnalysisRunRepository.class);
        when(runs.hasReceipt(EVENT_ID)).thenReturn(false);
        when(runs.findForWork(ANALYSIS_ID)).thenReturn(Optional.of(new AnalysisRunRepository.WorkView(
                ANALYSIS_ID, AnalysisStatus.CONTEXT_READY, null,
                new String(snapshotCodec.canonicalBytes(snapshot), StandardCharsets.UTF_8),
                snapshot.sha256(), null)));
        when(runs.updateStatus(any(), any(), any(), any(), any())).thenReturn(1);
        when(runs.insertResult(any(), any(Integer.class), anyString(), anyString(), any())).thenReturn(1);
        when(runs.insertReceipt(any(), any(), any())).thenReturn(1);

        ModelUsage usage = new ModelUsage(120, 25, Duration.ofMillis(42));
        AnalysisOrchestrator orchestrator = mock(AnalysisOrchestrator.class);
        when(orchestrator.execute(eq(ANALYSIS_ID), any(FactSnapshot.class)))
                .thenReturn(new AnalysisExecution(result(validFacts), "qwen", "phase3-model-v1:qwen:qwen-plus",
                        "review-analysis-v1", usage));
        ModelUsageRecorder recorder = mock(ModelUsageRecorder.class);
        TransactionTemplate transactions = mock(TransactionTemplate.class);
        doAnswer(invocation -> invocation.<org.springframework.transaction.support.TransactionCallback<?>>getArgument(0)
                .doInTransaction(null)).when(transactions).execute(any());

        AnalysisWorker worker = new AnalysisWorker(
                runs, snapshotCodec, orchestrator, new AnalysisOutputSchemaValidator(),
                new FactConsistencyValidator(), transactions, mock(RabbitTemplate.class), new ObjectMapper(),
                properties(), Clock.fixed(CAPTURED_AT, ZoneOffset.UTC), recorder);

        assertThat(worker.process(new AnalysisRequestedMessage(1, EVENT_ID, ANALYSIS_ID, CAPTURED_AT), 1))
                .isEqualTo(validFacts ? AnalysisWorker.WorkerOutcome.COMPLETED
                        : AnalysisWorker.WorkerOutcome.FAILED_GUARDRAIL);

        verify(recorder).record(ANALYSIS_ID, "qwen", "phase3-model-v1:qwen:qwen-plus",
                "review-analysis-v1", usage, validFacts ? "SUCCESS" : "FAILURE");
    }

    @Test
    void doesNotRetryModelFailureWhenSingleCallBudgetIsConfigured() {
        SnapshotCodec snapshotCodec = new SnapshotCodec();
        FactSnapshot snapshot = snapshotCodec.capture(facts());
        AnalysisRunRepository runs = mock(AnalysisRunRepository.class);
        when(runs.hasReceipt(EVENT_ID)).thenReturn(false);
        when(runs.findForWork(ANALYSIS_ID)).thenReturn(Optional.of(new AnalysisRunRepository.WorkView(
                ANALYSIS_ID, AnalysisStatus.CONTEXT_READY, null,
                new String(snapshotCodec.canonicalBytes(snapshot), StandardCharsets.UTF_8),
                snapshot.sha256(), null)));
        when(runs.updateStatus(any(), any(), any(), any(), any())).thenReturn(1);
        when(runs.insertReceipt(any(), any(), any())).thenReturn(1);

        AnalysisOrchestrator orchestrator = mock(AnalysisOrchestrator.class);
        when(orchestrator.execute(eq(ANALYSIS_ID), any(FactSnapshot.class)))
                .thenThrow(new ModelInvocationException(ErrorCode.MODEL_TIMEOUT, "qwen",
                        "phase3-model-v1:qwen:qwen-plus", "review-analysis-v1",
                        new ModelUsage(0, 0, Duration.ofSeconds(2)), "TIMEOUT"));
        ModelUsageRecorder recorder = mock(ModelUsageRecorder.class);
        TransactionTemplate transactions = mock(TransactionTemplate.class);
        doAnswer(invocation -> invocation.<org.springframework.transaction.support.TransactionCallback<?>>getArgument(0)
                .doInTransaction(null)).when(transactions).execute(any());
        RabbitTemplate rabbit = mock(RabbitTemplate.class);
        doAnswer(invocation -> {
            CorrelationData correlation = invocation.getArgument(4);
            correlation.getFuture().complete(new CorrelationData.Confirm(true, null));
            return null;
        }).when(rabbit).convertAndSend(anyString(), eq(RabbitTopologyConfiguration.ANALYSIS_ROUTING_KEY),
                any(AnalysisRequestedMessage.class), any(MessagePostProcessor.class), any(CorrelationData.class));

        AnalysisWorker worker = new AnalysisWorker(
                runs, snapshotCodec, orchestrator, new AnalysisOutputSchemaValidator(),
                new FactConsistencyValidator(), transactions, rabbit, new ObjectMapper(),
                properties(), Clock.fixed(CAPTURED_AT, ZoneOffset.UTC), recorder);

        assertThat(worker.process(new AnalysisRequestedMessage(1, EVENT_ID, ANALYSIS_ID, CAPTURED_AT), 1))
                .isEqualTo(AnalysisWorker.WorkerOutcome.DEAD_LETTERED);
        verify(rabbit, never()).convertAndSend(eq(RabbitTopologyConfiguration.RETRY_EXCHANGE),
                eq(RabbitTopologyConfiguration.ANALYSIS_ROUTING_KEY), any(AnalysisRequestedMessage.class),
                any(MessagePostProcessor.class), any(CorrelationData.class));
        verify(recorder).record(ANALYSIS_ID, "qwen", "phase3-model-v1:qwen:qwen-plus",
                "review-analysis-v1", new ModelUsage(0, 0, Duration.ofSeconds(2)), "TIMEOUT");
    }

    private AnalysisResult result(boolean validFacts) {
        return new AnalysisResult(
                AnalysisResult.CURRENT_SCHEMA_VERSION, ANALYSIS_ID, "summary", "explanation",
                List.of(new AnalysisResult.BusinessEvidence(validFacts ? 11 : 12, 42, new BigDecimal("12500.00"), "USD",
                        "RULE_HIGH_AMOUNT", "REASON_AMOUNT_THRESHOLD", List.of("transaction.amount"))),
                List.of(), AnalysisRecommendation.INSUFFICIENT_EVIDENCE, "insufficient",
                List.of("policy"), List.of("no published policy"));
    }

    private ReviewTaskContext facts() {
        return new ReviewTaskContext(
                new ReviewTaskContext.ReviewTask(7, 3, "OPEN", "RULE_HIGH_AMOUNT",
                        "REASON_AMOUNT_THRESHOLD", CAPTURED_AT),
                new ReviewTaskContext.Transaction(11, 42, new BigDecimal("12500.00"), "USD",
                        CAPTURED_AT, "BANK"),
                new ReviewTaskContext.Reconciliation("MATCHED", "REFERENCE_ID", "REASON_AMOUNT_THRESHOLD"),
                List.of(new ReviewTaskContext.RiskFact("HIGH_AMOUNT", "12500.00", "CORE")),
                new ReviewTaskContext.AccountSummary("ACTIVE", "masked-account"), CAPTURED_AT);
    }

    private CopilotProperties properties() {
        return new CopilotProperties(
                new CopilotProperties.Core(URI.create("https://core.example.internal"),
                        Duration.ofSeconds(2), Duration.ofSeconds(3)),
                new CopilotProperties.Embedding(8), new CopilotProperties.Retrieval(5),
                new CopilotProperties.Dispatch(false, 10, Duration.ofMillis(10), Duration.ofSeconds(1),
                        Duration.ofSeconds(1), Duration.ofSeconds(1), 3));
    }
}
