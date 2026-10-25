package com.finguard.copilot.dispatch;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.finguard.copilot.config.CopilotProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(
        prefix = "finguard.copilot.dispatch",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true)
public class OutboxRelay {

    private static final String BROKER_UNAVAILABLE = "BROKER_UNAVAILABLE";
    private static final String BROKER_PUBLISH_NACK = "BROKER_PUBLISH_NACK";
    private static final String BROKER_PUBLISH_RETURNED = "BROKER_PUBLISH_RETURNED";
    private static final String OUTBOX_BAD_PAYLOAD = "OUTBOX_BAD_PAYLOAD";
    private static final Instant QUARANTINE_AT = Instant.parse("9999-12-31T23:59:59.999999Z");

    private final OutboxRepository outbox;
    private final RabbitTemplate rabbitTemplate;
    private final ObjectMapper objectMapper;
    private final CopilotProperties properties;
    private final Clock clock;

    @Autowired
    public OutboxRelay(
            OutboxRepository outbox,
            RabbitTemplate rabbitTemplate,
            ObjectMapper objectMapper,
            CopilotProperties properties
    ) {
        this(outbox, rabbitTemplate, objectMapper, properties, Clock.systemUTC());
    }

    public OutboxRelay(
            OutboxRepository outbox,
            RabbitTemplate rabbitTemplate,
            ObjectMapper objectMapper,
            CopilotProperties properties,
            Clock clock
    ) {
        this.outbox = Objects.requireNonNull(outbox, "outbox must not be null");
        this.rabbitTemplate = Objects.requireNonNull(rabbitTemplate, "rabbitTemplate must not be null");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
        this.properties = Objects.requireNonNull(properties, "properties must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    @Scheduled(fixedDelayString = "${finguard.copilot.dispatch.relay-poll-interval:1s}")
    public void scheduledRelay() {
        if (properties.dispatch().relayEnabled()) {
            relayDue();
        }
    }

    public int relayDue() {
        Instant now = Instant.now(clock).truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        Duration lease = properties.dispatch().publisherConfirmTimeout().plusSeconds(1);
        List<OutboxRepository.OutboxEvent> events = outbox.claimDueEvents(
                now, properties.dispatch().outboxBatchSize(), lease);
        int processed = 0;
        for (OutboxRepository.OutboxEvent event : events) {
            if (publish(event)) {
                processed++;
            }
        }
        return processed;
    }

    private boolean publish(OutboxRepository.OutboxEvent event) {
        AnalysisRequestedMessage message;
        try {
            message = objectMapper.readValue(
                    event.payloadJson(), AnalysisRequestedMessage.class);
            if (message.schemaVersion() != event.schemaVersion()
                    || !message.eventId().equals(event.eventId())
                    || !message.analysisId().equals(event.analysisId())) {
                throw new IllegalArgumentException("outbox payload does not match its row");
            }
        } catch (com.fasterxml.jackson.core.JsonProcessingException | IllegalArgumentException exception) {
            outbox.recordPublishFailure(event.eventId(), OUTBOX_BAD_PAYLOAD, QUARANTINE_AT);
            return false;
        }

        try {
            CorrelationData correlation = new CorrelationData(event.eventId().toString());
            rabbitTemplate.convertAndSend(
                    RabbitTopologyConfiguration.ANALYSIS_EXCHANGE,
                    RabbitTopologyConfiguration.ANALYSIS_ROUTING_KEY,
                    message,
                    correlation);
            CorrelationData.Confirm confirm = correlation.getFuture().get(
                    properties.dispatch().publisherConfirmTimeout().toMillis(), TimeUnit.MILLISECONDS);
            if (confirm != null && confirm.isAck() && correlation.getReturned() == null) {
                Instant now = Instant.now(clock).truncatedTo(java.time.temporal.ChronoUnit.MICROS);
                return outbox.markPublished(event.eventId(), now) == 1;
            }
            Instant now = Instant.now(clock).truncatedTo(java.time.temporal.ChronoUnit.MICROS);
            recordFailure(event,
                    correlation.getReturned() == null ? BROKER_PUBLISH_NACK : BROKER_PUBLISH_RETURNED,
                    now);
        } catch (Exception exception) {
            Instant now = Instant.now(clock).truncatedTo(java.time.temporal.ChronoUnit.MICROS);
            recordFailure(event, BROKER_UNAVAILABLE, now);
        }
        return false;
    }

    private void recordFailure(OutboxRepository.OutboxEvent event, String errorCode, Instant now) {
        Duration delay = retryDelay(event.attemptCount());
        outbox.recordPublishFailure(event.eventId(), errorCode, now.plus(delay));
    }

    private Duration retryDelay(int attemptCount) {
        Duration initial = properties.dispatch().outboxRetryInitialDelay();
        Duration maximum = properties.dispatch().outboxRetryMaxDelay();
        long multiplier = attemptCount >= 30 ? (1L << 30) : (1L << Math.max(0, attemptCount));
        long seconds = initial.getSeconds() * multiplier;
        long nanos = initial.getNano() * multiplier;
        Duration calculated;
        try {
            calculated = Duration.ofSeconds(seconds, nanos);
        } catch (ArithmeticException exception) {
            calculated = maximum;
        }
        return calculated.compareTo(maximum) > 0 ? maximum : calculated;
    }
}
