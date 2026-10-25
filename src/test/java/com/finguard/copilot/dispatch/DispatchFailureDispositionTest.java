package com.finguard.copilot.dispatch;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.finguard.copilot.analysis.application.AnalysisOrchestrator;
import com.finguard.copilot.analysis.persistence.AnalysisRunRepository;
import com.finguard.copilot.config.CopilotProperties;
import com.finguard.copilot.context.SnapshotCodec;
import com.finguard.copilot.guardrail.AnalysisOutputSchemaValidator;
import com.finguard.copilot.guardrail.FactConsistencyValidator;
import com.rabbitmq.client.Channel;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessagePostProcessor;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.core.ReturnedMessage;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import org.mockito.ArgumentCaptor;

class DispatchFailureDispositionTest {

    private static final Instant NOW = Instant.parse("2026-08-26T00:00:00Z");
    private static final UUID EVENT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID ANALYSIS_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final ObjectMapper MAPPER = JsonMapper.builder()
            .addModule(new JavaTimeModule())
            .build();

    @Test
    void returnedPublisherMessageIsFailureEvenWhenConfirmIsPositive() {
        OutboxRepository outbox = mock(OutboxRepository.class);
        RabbitTemplate rabbit = mock(RabbitTemplate.class);
        OutboxRepository.OutboxEvent event = event(validPayload());
        when(outbox.claimDueEvents(any(), anyInt(), any())).thenReturn(List.of(event));
        doAnswer(invocation -> {
            CorrelationData correlation = invocation.getArgument(3);
            correlation.setReturned(new ReturnedMessage(
                    new Message(new byte[] {1}), 312, "NO_ROUTE",
                    RabbitTopologyConfiguration.ANALYSIS_EXCHANGE,
                    RabbitTopologyConfiguration.ANALYSIS_ROUTING_KEY));
            correlation.getFuture().complete(new CorrelationData.Confirm(true, null));
            return null;
        }).when(rabbit).convertAndSend(
                eq(RabbitTopologyConfiguration.ANALYSIS_EXCHANGE),
                eq(RabbitTopologyConfiguration.ANALYSIS_ROUTING_KEY),
                any(AnalysisRequestedMessage.class), any(CorrelationData.class));

        new OutboxRelay(outbox, rabbit, MAPPER, properties(), Clock.fixed(NOW, java.time.ZoneOffset.UTC))
                .relayDue();

        verify(outbox, never()).markPublished(eq(EVENT_ID), any());
        verify(outbox).recordPublishFailure(eq(EVENT_ID), eq("BROKER_PUBLISH_RETURNED"), any());
    }

    @Test
    void invalidOutboxPayloadIsQuarantinedAsDeterministicFailure() {
        OutboxRepository outbox = mock(OutboxRepository.class);
        RabbitTemplate rabbit = mock(RabbitTemplate.class);
        OutboxRepository.OutboxEvent event = event("not-json");
        when(outbox.claimDueEvents(any(), anyInt(), any())).thenReturn(List.of(event));

        new OutboxRelay(outbox, rabbit, MAPPER, properties(), Clock.fixed(NOW, java.time.ZoneOffset.UTC))
                .relayDue();

        ArgumentCaptor<Instant> nextAttempt = ArgumentCaptor.forClass(Instant.class);
        verify(outbox).recordPublishFailure(eq(EVENT_ID), eq("OUTBOX_BAD_PAYLOAD"), nextAttempt.capture());
        assertThat(nextAttempt.getValue()).isAfter(Instant.parse("2099-01-01T00:00:00Z"));
        verify(outbox, never()).markPublished(any(), any());
    }

    @Test
    void brokerOutageCanRecoverOnTheNextDueRelayAttempt() {
        OutboxRepository outbox = mock(OutboxRepository.class);
        RabbitTemplate rabbit = mock(RabbitTemplate.class);
        OutboxRepository.OutboxEvent event = event(validPayload());
        when(outbox.claimDueEvents(any(), anyInt(), any())).thenReturn(List.of(event));
        when(outbox.markPublished(eq(EVENT_ID), any())).thenReturn(1);
        org.mockito.stubbing.Answer<Object> publish = new org.mockito.stubbing.Answer<>() {
            private int calls;

            @Override
            public Object answer(org.mockito.invocation.InvocationOnMock invocation) {
                if (++calls == 1) {
                    throw new AmqpException("broker unavailable");
                }
                CorrelationData correlation = invocation.getArgument(3);
                correlation.getFuture().complete(new CorrelationData.Confirm(true, null));
                return null;
            }
        };
        doAnswer(publish).when(rabbit).convertAndSend(
                eq(RabbitTopologyConfiguration.ANALYSIS_EXCHANGE),
                eq(RabbitTopologyConfiguration.ANALYSIS_ROUTING_KEY),
                any(AnalysisRequestedMessage.class), any(CorrelationData.class));

        OutboxRelay relay = new OutboxRelay(outbox, rabbit, MAPPER, properties(),
                Clock.fixed(NOW, java.time.ZoneOffset.UTC));
        assertThat(relay.relayDue()).isZero();
        assertThat(relay.relayDue()).isEqualTo(1);

        verify(outbox).recordPublishFailure(eq(EVENT_ID), eq("BROKER_UNAVAILABLE"), any());
        verify(outbox).markPublished(eq(EVENT_ID), any());
    }

    @Test
    void retryQueueKeepsItsProductionThirtySecondTtl() {
        RabbitTopologyConfiguration configuration = new RabbitTopologyConfiguration();

        assertThat(configuration.retryQueue().getArguments())
                .containsEntry("x-message-ttl", 30_000L);
    }

    @Test
    void returnedWorkerRetryFallsBackToConfirmedDlqInsteadOfHotRequeue() {
        RabbitTemplate rabbit = mock(RabbitTemplate.class);
        AtomicPublishBehavior publish = new AtomicPublishBehavior(rabbit, true, false);
        AnalysisWorker worker = failingWorker(rabbit);

        AnalysisWorker.WorkerOutcome outcome = worker.process(message(), 1);

        assertThat(outcome).isEqualTo(AnalysisWorker.WorkerOutcome.DEAD_LETTERED);
        assertThat(publish.retryPublishCount).isEqualTo(1);
        assertThat(publish.dlqPublishCount).isEqualTo(1);
    }

    @Test
    void onMessageRequeuesWhenRetryAndDlqPublicationFail() throws Exception {
        RabbitTemplate rabbit = mock(RabbitTemplate.class);
        doThrow(new AmqpException("broker unavailable")).when(rabbit).convertAndSend(
                anyString(), eq(RabbitTopologyConfiguration.ANALYSIS_ROUTING_KEY),
                any(AnalysisRequestedMessage.class), any(MessagePostProcessor.class),
                any(CorrelationData.class));
        AnalysisWorker worker = failingWorker(rabbit);
        Channel channel = mock(Channel.class);
        MessageProperties properties = new MessageProperties();
        properties.setDeliveryTag(43L);

        worker.onMessage(new Message(MAPPER.writeValueAsBytes(message()), properties), channel);

        verify(channel).basicNack(43L, false, true);
        verify(channel, never()).basicAck(anyLong(), anyBoolean());
    }

    @Test
    void workerPublishesRetryAndDlqWithBoundedAttemptHeaders() {
        RabbitTemplate rabbit = mock(RabbitTemplate.class);
        AtomicPublishBehavior publish = new AtomicPublishBehavior(rabbit, false, false);
        AnalysisWorker worker = failingWorker(rabbit);

        AnalysisWorker.WorkerOutcome outcome = worker.process(message(), 3);

        assertThat(outcome).isEqualTo(AnalysisWorker.WorkerOutcome.DEAD_LETTERED);
        assertThat(publish.lastAttempt).isEqualTo(3);
        assertThat(publish.dlqPublishCount).isEqualTo(1);
    }

    @Test
    void malformedMessageRequiresConfirmedDlqBeforeAcknowledgement() throws Exception {
        RabbitTemplate rabbit = mock(RabbitTemplate.class);
        completeRawSend(rabbit, false);
        Channel channel = mock(Channel.class);
        MessageProperties properties = new MessageProperties();
        properties.setDeliveryTag(41L);
        AnalysisWorker worker = worker(rabbit);

        worker.onMessage(new Message("broken".getBytes(java.nio.charset.StandardCharsets.UTF_8), properties), channel);

        verify(rabbit).send(eq(RabbitTopologyConfiguration.DLX_EXCHANGE),
                eq(RabbitTopologyConfiguration.ANALYSIS_ROUTING_KEY), any(Message.class), any(CorrelationData.class));
        verify(channel).basicAck(41L, false);
        verify(channel, never()).basicNack(anyLong(), anyBoolean(), anyBoolean());
    }

    @Test
    void malformedMessageIsRequeuedWhenDlqCannotBeConfirmed() throws Exception {
        RabbitTemplate rabbit = mock(RabbitTemplate.class);
        doThrow(new AmqpException("broker unavailable")).when(rabbit).send(
                eq(RabbitTopologyConfiguration.DLX_EXCHANGE),
                eq(RabbitTopologyConfiguration.ANALYSIS_ROUTING_KEY), any(Message.class), any(CorrelationData.class));
        Channel channel = mock(Channel.class);
        MessageProperties properties = new MessageProperties();
        properties.setDeliveryTag(42L);
        AnalysisWorker worker = worker(rabbit);

        worker.onMessage(new Message("broken".getBytes(java.nio.charset.StandardCharsets.UTF_8), properties), channel);

        verify(channel).basicNack(42L, false, true);
        verify(channel, never()).basicAck(anyLong(), anyBoolean());
    }

    private static AnalysisWorker failingWorker(RabbitTemplate rabbit) {
        TransactionTemplate transactions = mock(TransactionTemplate.class);
        when(transactions.execute(any())).thenThrow(new IllegalStateException("analysis outage"));
        return worker(transactions, rabbit);
    }

    private static AnalysisWorker worker(RabbitTemplate rabbit) {
        return worker(mock(TransactionTemplate.class), rabbit);
    }

    private static AnalysisWorker worker(TransactionTemplate transactions, RabbitTemplate rabbit) {
        return new AnalysisWorker(
                mock(AnalysisRunRepository.class),
                mock(SnapshotCodec.class),
                mock(AnalysisOrchestrator.class),
                mock(AnalysisOutputSchemaValidator.class),
                mock(FactConsistencyValidator.class),
                transactions,
                rabbit,
                MAPPER,
                properties(),
                Clock.fixed(NOW, java.time.ZoneOffset.UTC));
    }

    private static void completeRawSend(RabbitTemplate rabbit, boolean returned) {
        doAnswer(invocation -> {
            CorrelationData correlation = invocation.getArgument(3);
            if (returned) {
                correlation.setReturned(new ReturnedMessage(
                        new Message(new byte[] {1}), 312, "NO_ROUTE",
                        RabbitTopologyConfiguration.DLX_EXCHANGE,
                        RabbitTopologyConfiguration.ANALYSIS_ROUTING_KEY));
            }
            correlation.getFuture().complete(new CorrelationData.Confirm(true, null));
            return null;
        }).when(rabbit).send(eq(RabbitTopologyConfiguration.DLX_EXCHANGE),
                eq(RabbitTopologyConfiguration.ANALYSIS_ROUTING_KEY), any(Message.class), any(CorrelationData.class));
    }

    private static CopilotProperties properties() {
        return new CopilotProperties(
                new CopilotProperties.Core(URI.create("https://core.example.internal"),
                        Duration.ofSeconds(2), Duration.ofSeconds(3)),
                new CopilotProperties.Embedding(8),
                new CopilotProperties.Retrieval(5),
                new CopilotProperties.Dispatch(false, 10, Duration.ofMillis(10), Duration.ofSeconds(1),
                        Duration.ofSeconds(1), Duration.ofSeconds(1), 3));
    }

    private static OutboxRepository.OutboxEvent event(String payload) {
        return new OutboxRepository.OutboxEvent(EVENT_ID, ANALYSIS_ID, 1, payload, 0, NOW, NOW);
    }

    private static String validPayload() {
        return "{\"schemaVersion\":1,\"eventId\":\"" + EVENT_ID
                + "\",\"analysisId\":\"" + ANALYSIS_ID
                + "\",\"occurredAt\":\"" + NOW + "\"}";
    }

    private static AnalysisRequestedMessage message() {
        return new AnalysisRequestedMessage(1, EVENT_ID, ANALYSIS_ID, NOW);
    }

    private static final class AtomicPublishBehavior {
        private int retryPublishCount;
        private int dlqPublishCount;
        private int lastAttempt;

        private AtomicPublishBehavior(RabbitTemplate rabbit, boolean returnedRetry, boolean returnedDlq) {
            doAnswer(invocation -> {
                String exchange = invocation.getArgument(0);
                MessagePostProcessor processor = invocation.getArgument(3);
                CorrelationData correlation = invocation.getArgument(4);
                Message outbound = processor.postProcessMessage(new Message(new byte[0], new MessageProperties()));
                lastAttempt = (Integer) outbound.getMessageProperties().getHeader("x-worker-attempt");
                if (RabbitTopologyConfiguration.RETRY_EXCHANGE.equals(exchange)) {
                    retryPublishCount++;
                    if (returnedRetry) {
                        correlation.setReturned(new ReturnedMessage(
                                new Message(new byte[] {1}), 312, "NO_ROUTE", exchange,
                                RabbitTopologyConfiguration.ANALYSIS_ROUTING_KEY));
                    }
                } else {
                    dlqPublishCount++;
                    if (returnedDlq) {
                        correlation.setReturned(new ReturnedMessage(
                                new Message(new byte[] {1}), 312, "NO_ROUTE", exchange,
                                RabbitTopologyConfiguration.ANALYSIS_ROUTING_KEY));
                    }
                }
                correlation.getFuture().complete(new CorrelationData.Confirm(true, null));
                return null;
            }).when(rabbit).convertAndSend(anyString(), eq(RabbitTopologyConfiguration.ANALYSIS_ROUTING_KEY),
                    any(AnalysisRequestedMessage.class), any(MessagePostProcessor.class), any(CorrelationData.class));
        }
    }
}
