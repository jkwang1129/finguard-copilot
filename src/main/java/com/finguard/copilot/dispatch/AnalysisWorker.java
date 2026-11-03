package com.finguard.copilot.dispatch;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finguard.copilot.analysis.application.AnalysisOrchestrator;
import com.finguard.copilot.analysis.application.AnalysisExecution;
import com.finguard.copilot.analysis.application.AnalysisStateMachine;
import com.finguard.copilot.analysis.application.ModelInvocationException;
import com.finguard.copilot.analysis.domain.AnalysisResult;
import com.finguard.copilot.analysis.domain.AnalysisStatus;
import com.finguard.copilot.analysis.persistence.AnalysisRunRepository;
import com.finguard.copilot.common.error.CopilotException;
import com.finguard.copilot.common.error.ErrorCode;
import com.finguard.copilot.config.CopilotProperties;
import com.finguard.copilot.context.FactSnapshot;
import com.finguard.copilot.context.SnapshotCodec;
import com.finguard.copilot.guardrail.AnalysisOutputSchemaValidator;
import com.finguard.copilot.guardrail.FactConsistencyValidator;
import com.finguard.copilot.usage.ModelUsageRecorder;
import com.finguard.copilot.usage.ModelCallReservations;
import com.rabbitmq.client.Channel;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessagePostProcessor;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Component
@ConditionalOnProperty(
        prefix = "finguard.copilot.dispatch",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true)
public class AnalysisWorker {

    private static final String ATTEMPT_HEADER = "x-worker-attempt";
    private static final String RETRYABLE_ERROR = "ANALYSIS_RETRY_EXHAUSTED";
    private static final Logger LOG = LoggerFactory.getLogger(AnalysisWorker.class);

    private final AnalysisRunRepository runs;
    private final SnapshotCodec snapshotCodec;
    private final AnalysisOrchestrator orchestrator;
    private final AnalysisOutputSchemaValidator schemaValidator;
    private final FactConsistencyValidator factValidator;
    private final TransactionTemplate transactions;
    private final RabbitTemplate rabbitTemplate;
    private final ObjectMapper objectMapper;
    private final CopilotProperties properties;
    private final Clock clock;
    private final ModelUsageRecorder usageRecorder;
    private final ModelCallReservations reservations;
    private final AnalysisStateMachine stateMachine = new AnalysisStateMachine();
    private com.finguard.copilot.observability.AnalysisTelemetry telemetry=com.finguard.copilot.observability.AnalysisTelemetry.noop();
    @Autowired public void setTelemetry(com.finguard.copilot.observability.AnalysisTelemetry value) {telemetry=Objects.requireNonNull(value);}

    @Autowired
    public AnalysisWorker(
            AnalysisRunRepository runs,
            SnapshotCodec snapshotCodec,
            AnalysisOrchestrator orchestrator,
            AnalysisOutputSchemaValidator schemaValidator,
            FactConsistencyValidator factValidator,
            TransactionTemplate transactions,
            RabbitTemplate rabbitTemplate,
            ObjectMapper objectMapper,
            CopilotProperties properties,
            ModelUsageRecorder usageRecorder,
            ModelCallReservations reservations
    ) {
        this(runs, snapshotCodec, orchestrator, schemaValidator, factValidator, transactions,
                rabbitTemplate, objectMapper, properties, Clock.systemUTC(), usageRecorder, reservations);
    }

    public AnalysisWorker(
            AnalysisRunRepository runs,
            SnapshotCodec snapshotCodec,
            AnalysisOrchestrator orchestrator,
            AnalysisOutputSchemaValidator schemaValidator,
            FactConsistencyValidator factValidator,
            TransactionTemplate transactions,
            RabbitTemplate rabbitTemplate,
            ObjectMapper objectMapper,
            CopilotProperties properties
    ) {
        this(runs, snapshotCodec, orchestrator, schemaValidator, factValidator, transactions,
                rabbitTemplate, objectMapper, properties, Clock.systemUTC(), null, null);
    }

    public AnalysisWorker(
            AnalysisRunRepository runs,
            SnapshotCodec snapshotCodec,
            AnalysisOrchestrator orchestrator,
            AnalysisOutputSchemaValidator schemaValidator,
            FactConsistencyValidator factValidator,
            TransactionTemplate transactions,
            RabbitTemplate rabbitTemplate,
            ObjectMapper objectMapper,
            CopilotProperties properties,
            Clock clock
    ) {
        this(runs, snapshotCodec, orchestrator, schemaValidator, factValidator, transactions,
                rabbitTemplate, objectMapper, properties, clock, null, null);
    }

    public AnalysisWorker(
            AnalysisRunRepository runs,
            SnapshotCodec snapshotCodec,
            AnalysisOrchestrator orchestrator,
            AnalysisOutputSchemaValidator schemaValidator,
            FactConsistencyValidator factValidator,
            TransactionTemplate transactions,
            RabbitTemplate rabbitTemplate,
            ObjectMapper objectMapper,
            CopilotProperties properties,
            Clock clock,
            ModelUsageRecorder usageRecorder
    ) {
        this(runs, snapshotCodec, orchestrator, schemaValidator, factValidator, transactions,
                rabbitTemplate, objectMapper, properties, clock, usageRecorder, null);
    }

    public AnalysisWorker(
            AnalysisRunRepository runs,
            SnapshotCodec snapshotCodec,
            AnalysisOrchestrator orchestrator,
            AnalysisOutputSchemaValidator schemaValidator,
            FactConsistencyValidator factValidator,
            TransactionTemplate transactions,
            RabbitTemplate rabbitTemplate,
            ObjectMapper objectMapper,
            CopilotProperties properties,
            Clock clock,
            ModelUsageRecorder usageRecorder,
            ModelCallReservations reservations
    ) {
        this.runs = Objects.requireNonNull(runs, "runs must not be null");
        this.snapshotCodec = Objects.requireNonNull(snapshotCodec, "snapshotCodec must not be null");
        this.orchestrator = Objects.requireNonNull(orchestrator, "orchestrator must not be null");
        this.schemaValidator = Objects.requireNonNull(schemaValidator, "schemaValidator must not be null");
        this.factValidator = Objects.requireNonNull(factValidator, "factValidator must not be null");
        this.transactions = Objects.requireNonNull(transactions, "transactions must not be null");
        this.rabbitTemplate = Objects.requireNonNull(rabbitTemplate, "rabbitTemplate must not be null");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
        this.properties = Objects.requireNonNull(properties, "properties must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.usageRecorder = usageRecorder;
        this.reservations = reservations;
    }

    @RabbitListener(queues = RabbitTopologyConfiguration.ANALYSIS_QUEUE, ackMode = "MANUAL")
    public void onMessage(Message message, Channel channel) throws IOException {
        int attempt = attempt(message);
        AnalysisRequestedMessage request;
        try {
            request = objectMapper.readValue(message.getBody(), AnalysisRequestedMessage.class);
        } catch (IOException | IllegalArgumentException exception) {
            if (publishMalformedToDlq(message)) {
                channel.basicAck(message.getMessageProperties().getDeliveryTag(), false);
            } else {
                LOG.warn("malformed analysis message received but DLQ publication was not confirmed");
                channel.basicNack(message.getMessageProperties().getDeliveryTag(), false, true);
            }
            return;
        }
        try {
            WorkerOutcome outcome = process(request, attempt);
            if (outcome == WorkerOutcome.PUBLISH_FAILED) {
                channel.basicNack(message.getMessageProperties().getDeliveryTag(), false, true);
            } else {
                channel.basicAck(message.getMessageProperties().getDeliveryTag(), false);
            }
        } catch (RuntimeException exception) {
            LOG.warn("analysis message received an unrecoverable worker disposition: {}",
                    exception.getClass().getSimpleName());
            channel.basicNack(message.getMessageProperties().getDeliveryTag(), false, true);
        }
    }

    public WorkerOutcome process(AnalysisRequestedMessage message, int attempt) {
        if(message==null || attempt<1) throw new IllegalArgumentException("worker message and attempt are invalid");
        try(var trace=telemetry.start(com.finguard.copilot.observability.AnalysisTelemetry.Stage.WORKER,message.analysisId(),message.traceparent())) {
            try {
                var outcome=processObserved(message,attempt);
                trace.outcome(switch(outcome) {
                    case COMPLETED,COMPLETED_FROM_STORED_RESULT -> "SUCCESS";
                    case NOOP -> "NOOP";
                    case RETRIED -> "RETRY";
                    default -> "FAILURE";
                });
                return outcome;
            } catch(RuntimeException e) {trace.failure(e instanceof CopilotException c?c.code().name():"INTERNAL_ERROR");throw e;}
        }
    }
    private WorkerOutcome processObserved(AnalysisRequestedMessage message,int attempt) {
        if (message == null || attempt < 1) {
            throw new IllegalArgumentException("worker message and attempt are invalid");
        }
        if (orchestrator.requiresModelCall()) {
            return processModel(message, attempt);
        }
        try {
            WorkerOutcome outcome = transactions.execute(status -> processTransaction(message));
            return outcome == null ? WorkerOutcome.NOOP : outcome;
        } catch (ModelInvocationException exception) {
            recordFailure(message.analysisId(), exception);
            return recoverAfterWorkerFailure(message, attempt, true);
        } catch (RuntimeException exception) {
            return recoverAfterWorkerFailure(message, attempt, false);
        }
    }

    private WorkerOutcome processModel(AnalysisRequestedMessage message, int attempt) {
        if (reservations == null || usageRecorder == null) {
            throw new IllegalStateException("model call protection and usage recorder must be configured");
        }
        ModelPreflight preflight;
        try {
            preflight = transactions.execute(status -> prepareModelCall(message));
        } catch (CopilotException rejected) {
            if(orchestrator.managesModelReservation()) return recoverUnownedPreparation(message,attempt,rejected.code().name(),
                    rejected.code()!=ErrorCode.FACT_VALIDATION_FAILED && rejected.code()!=ErrorCode.OUTPUT_SCHEMA_INVALID && rejected.code()!=ErrorCode.MODEL_OUTPUT_INVALID);
            if(rejected.code()==ErrorCode.FACT_VALIDATION_FAILED || rejected.code()==ErrorCode.OUTPUT_SCHEMA_INVALID
                    || rejected.code()==ErrorCode.MODEL_OUTPUT_INVALID) {
                transactions.executeWithoutResult(status->markDeterministicFailure(message,rejected.code().name(),now()));
                return WorkerOutcome.FAILED_GUARDRAIL;
            }
            return recoverAfterWorkerFailure(message,attempt,false);
        } catch (RuntimeException exception) {
            if(orchestrator.managesModelReservation()) return recoverUnownedPreparation(message,attempt,"MODEL_PREPARATION_UNAVAILABLE",true);
            return recoverAfterWorkerFailure(message, attempt, false);
        }
        if (preflight == null) {
            return WorkerOutcome.NOOP;
        }
        if (preflight.outcome() != null) {
            return preflight.outcome();
        }
        boolean newlyReserved;
        try {
            newlyReserved = orchestrator.managesModelReservation()
                    ? reservations.reservedAt(message.analysisId()).isEmpty()
                    : reservations.reserve(message.analysisId(), now());
        } catch (RuntimeException exception) {
            if(orchestrator.managesModelReservation()) return recoverUnownedPreparation(message,attempt,"MODEL_PREPARATION_UNAVAILABLE",true);
            return recoverAfterWorkerFailure(message, attempt, false);
        }
        if (!newlyReserved) {
            return dispositionForReservedCall(message, attempt);
        }
        AnalysisExecution execution;
        try {
            execution = orchestrator.execute(message.analysisId(), preflight.snapshot());
        } catch (com.finguard.copilot.governance.AnalysisAdmissionClosedException closed) {
            return WorkerOutcome.NOOP;
        } catch (com.finguard.copilot.governance.ExistingModelCallException existing) {
            return dispositionForReservedCall(message,attempt);
        } catch (com.finguard.copilot.governance.ClaimedCallNotSentException notSent) {
            return terminalModelDisposition(message,notSent.code().name());
        } catch (ModelInvocationException exception) {
            try {
                recordFailure(message.analysisId(), exception);
            } catch (RuntimeException ledgerFailure) {
                LOG.warn("model failure usage could not be recorded for analysis {}: {}",
                        message.analysisId(), ledgerFailure.getClass().getSimpleName());
            }
            return terminalModelDisposition(message, exception.errorCode().name(),exception.usage());
        } catch (CopilotException exception) {
            if(orchestrator.managesModelReservation()) return recoverUnownedPreparation(message,attempt,exception.code().name(),
                    exception.code()==ErrorCode.MODEL_CAPACITY_UNAVAILABLE);
            if(exception.code()==ErrorCode.MODEL_CONFIGURATION_INVALID || exception.code()==ErrorCode.MODEL_BUDGET_EXCEEDED)
                return terminalModelDisposition(message,exception.code().name());
            if(exception.code()==ErrorCode.MODEL_CAPACITY_UNAVAILABLE)
                return terminalModelDisposition(message,exception.code().name());
            return terminalModelDisposition(message,"MODEL_CALL_OUTCOME_UNKNOWN");
        } catch (RuntimeException exception) {
            if(orchestrator.managesModelReservation()) return recoverUnownedPreparation(message,attempt,"MODEL_PREPARATION_UNAVAILABLE",true);
            LOG.warn("reserved model execution failed for analysis {}: {}",
                    message.analysisId(), exception.getClass().getSimpleName());
            return terminalModelDisposition(message, "MODEL_CALL_OUTCOME_UNKNOWN");
        }
        AnalysisResult validated;
        try {
            validated = telemetry.call(com.finguard.copilot.observability.AnalysisTelemetry.Stage.VALIDATION,()->{
                var checked=schemaValidator.validate(execution.result());
                factValidator.validate(message.analysisId(),preflight.snapshot(),checked);return checked;
            });
        } catch (RuntimeException exception) {
            try {
                recordRejectedExecution(message.analysisId(), execution);
            } catch (RuntimeException ledgerFailure) {
                LOG.warn("rejected model usage could not be recorded for analysis {}: {}",
                        message.analysisId(), ledgerFailure.getClass().getSimpleName());
            }
            // A usage write can roll back while the provider has already charged.
            // Persist that uncertainty before making the failure/receipt final.
            orchestrator.modelCallOutcomeUnknown(message.analysisId(),execution.usage());
            try {
                transactions.executeWithoutResult(status ->
                        markDeterministicFailure(message, ErrorCode.FACT_VALIDATION_FAILED.name(), now()));
                return WorkerOutcome.FAILED_GUARDRAIL;
            } catch (RuntimeException persistenceFailure) {
                return terminalModelDisposition(message, "MODEL_CALL_OUTCOME_UNKNOWN",execution.usage());
            }
        }
        try {
            telemetry.run(com.finguard.copilot.observability.AnalysisTelemetry.Stage.ACCOUNTING,()->recordSuccess(message.analysisId(), execution));
            WorkerOutcome outcome = telemetry.call(com.finguard.copilot.observability.AnalysisTelemetry.Stage.PERSISTENCE,()->transactions.execute(status -> finishModelCall(message, validated)));
            return outcome == null ? WorkerOutcome.NOOP : outcome;
        } catch (RuntimeException exception) {
            return terminalModelDisposition(message, "MODEL_CALL_OUTCOME_UNKNOWN",execution.usage());
        }
    }

    private WorkerOutcome recoverUnownedPreparation(AnalysisRequestedMessage message,int attempt,String failureCode,boolean retryable) {
        if(retryable && attempt<properties.dispatch().workerMaxAttempts()) {
            try {
                if(reservations.reservedAt(message.analysisId()).isPresent()) return dispositionForReservedCall(message,attempt);
                publishWithConfirm(RabbitTopologyConfiguration.RETRY_EXCHANGE,message,attempt+1);
                return WorkerOutcome.RETRIED;
            } catch(RuntimeException unavailable) { /* The locked terminal decision below remains safe. */ }
        }
        try {
            WorkerOutcome decision=transactions.execute(status->{
                var work=runs.findForWork(message.analysisId()).orElse(null);
                if(work==null) return WorkerOutcome.NOOP;
                if(stateMachine.isTerminal(work.status())) {
                    if(!runs.hasReceipt(message.eventId())) insertReceipt(message,now());
                    return WorkerOutcome.NOOP;
                }
                // Same analysis lock and current slot read as budget admission.
                // Never make another worker's active call terminal.
                if(reservations.reservedAt(message.analysisId()).isPresent()) return null;
                publishWithConfirm(RabbitTopologyConfiguration.DLX_EXCHANGE,message,attempt);
                markRetryExhausted(message,failureCode);
                return WorkerOutcome.DEAD_LETTERED;
            });
            return decision==null?dispositionForReservedCall(message,attempt):decision;
        } catch(RuntimeException unavailable) {return WorkerOutcome.PUBLISH_FAILED;}
    }

    private ModelPreflight prepareModelCall(AnalysisRequestedMessage message) {
        Instant now = now();
        if (runs.hasReceipt(message.eventId())) {
            return new ModelPreflight(null, WorkerOutcome.NOOP);
        }
        AnalysisRunRepository.WorkView work = runs.findForWork(message.analysisId()).orElse(null);
        if (work == null) {
            return new ModelPreflight(null, WorkerOutcome.NOOP);
        }
        if (stateMachine.isTerminal(work.status())) {
            insertReceipt(message, now);
            return new ModelPreflight(null, WorkerOutcome.NOOP);
        }
        FactSnapshot snapshot = loadSnapshot(work);
        if (work.resultJson() != null && !work.resultJson().isBlank()) {
            AnalysisResult stored = schemaValidator.validate(work.resultJson());
            factValidator.validate(message.analysisId(), snapshot, stored);
            orchestrator.validateStoredResult(snapshot,stored);
            completeForwardTransitions(message.analysisId(), work.status(), now);
            insertReceipt(message, now);
            return new ModelPreflight(null, WorkerOutcome.COMPLETED_FROM_STORED_RESULT);
        }
        return new ModelPreflight(snapshot, null);
    }

    private WorkerOutcome finishModelCall(AnalysisRequestedMessage message, AnalysisResult validated) {
        Instant now = now();
        if (runs.hasReceipt(message.eventId())) {
            return WorkerOutcome.NOOP;
        }
        AnalysisRunRepository.WorkView work = runs.findForWork(message.analysisId()).orElse(null);
        if (work == null || stateMachine.isTerminal(work.status())) {
            return WorkerOutcome.NOOP;
        }
        completeForwardTransitions(message.analysisId(), work.status(), now, false);
        runs.insertResult(message.analysisId(), validated.schemaVersion(), validated.recommendation().name(),
                serialize(validated), now);
        setStatus(message.analysisId(), AnalysisStatus.VALIDATING, AnalysisStatus.SUCCEEDED, null, now);
        insertReceipt(message, now);
        return WorkerOutcome.COMPLETED;
    }

    private WorkerOutcome dispositionForReservedCall(AnalysisRequestedMessage message, int attempt) {
        Instant reservedAt = reservations.reservedAt(message.analysisId())
                .orElseThrow(() -> new IllegalStateException("model call reservation vanished"));
        var recoveryWindow=properties.model().timeout().multipliedBy(2).plusSeconds(30);
        long maximumProbes=Math.max(properties.dispatch().workerMaxAttempts(),
                recoveryWindow.toMillis()/RabbitTopologyConfiguration.RETRY_QUEUE_TTL.toMillis()+3);
        if (attempt<maximumProbes && now().isBefore(reservedAt.plus(recoveryWindow))) {
            try {
                publishWithConfirm(RabbitTopologyConfiguration.RETRY_EXCHANGE, message, attempt + 1);
                return WorkerOutcome.RETRIED;
            } catch (RuntimeException exception) {
                return WorkerOutcome.PUBLISH_FAILED;
            }
        }
        return terminalModelDisposition(message, "MODEL_CALL_OUTCOME_UNKNOWN");
    }

    private record ModelPreflight(FactSnapshot snapshot, WorkerOutcome outcome) {
    }

    private WorkerOutcome terminalModelDisposition(AnalysisRequestedMessage message, String failureCode) {
        return terminalModelDisposition(message,failureCode,null);
    }
    private WorkerOutcome terminalModelDisposition(AnalysisRequestedMessage message,String failureCode,com.finguard.copilot.model.port.ModelUsage usage) {
        if(usage==null) orchestrator.modelCallOutcomeUnknown(message.analysisId());
        else orchestrator.modelCallOutcomeUnknown(message.analysisId(),usage);
        boolean dlqConfirmed = true;
        try {
            publishWithConfirm(RabbitTopologyConfiguration.DLX_EXCHANGE, message, 1);
        } catch (RuntimeException exception) {
            dlqConfirmed = false;
            LOG.warn("model call DLQ publication was not confirmed for analysis {}", message.analysisId());
        }
        transactions.executeWithoutResult(status -> markRetryExhausted(message, failureCode));
        return dlqConfirmed ? WorkerOutcome.DEAD_LETTERED : WorkerOutcome.PUBLISH_FAILED;
    }

    private WorkerOutcome recoverAfterWorkerFailure(
            AnalysisRequestedMessage message, int attempt, boolean modelFailure) {
        boolean modelCallBudgetExhausted = modelFailure && attempt >= properties.budget().maxModelCalls();
        if (!modelCallBudgetExhausted && attempt < properties.dispatch().workerMaxAttempts()) {
            try {
                publishWithConfirm(RabbitTopologyConfiguration.RETRY_EXCHANGE,
                        message, attempt + 1);
                return WorkerOutcome.RETRIED;
            } catch (RuntimeException publishFailure) {
                return terminalDispositionAfterPublishFailure(message, publishFailure);
            }
        }
        return terminalDispositionAfterPublishFailure(message, null);
    }

    private WorkerOutcome terminalDispositionAfterPublishFailure(
            AnalysisRequestedMessage message, RuntimeException retryFailure) {
        boolean dlqConfirmed = true;
        try {
            publishWithConfirm(RabbitTopologyConfiguration.DLX_EXCHANGE, message,
                    Math.min(properties.dispatch().workerMaxAttempts(), 3));
        } catch (RuntimeException dlqFailure) {
            dlqConfirmed = false;
            LOG.warn("analysis message DLQ publication was not confirmed: {}",
                    dlqFailure.getClass().getSimpleName());
        }
        if (retryFailure != null) {
            LOG.warn("analysis retry publication was not confirmed: {}",
                    retryFailure.getClass().getSimpleName());
        }
        transactions.executeWithoutResult(status -> markRetryExhausted(message));
        return dlqConfirmed ? WorkerOutcome.DEAD_LETTERED : WorkerOutcome.PUBLISH_FAILED;
    }

    private WorkerOutcome processTransaction(AnalysisRequestedMessage message) {
        Instant now = now();
        if (runs.hasReceipt(message.eventId())) {
            return WorkerOutcome.NOOP;
        }
        AnalysisRunRepository.WorkView work = runs.findForWork(message.analysisId()).orElse(null);
        if (work == null) {
            return WorkerOutcome.NOOP;
        }
        if (stateMachine.isTerminal(work.status())) {
            insertReceipt(message, now);
            return WorkerOutcome.NOOP;
        }
        AnalysisExecution execution = null;
        try {
            FactSnapshot snapshot = loadSnapshot(work);
            if (work.resultJson() != null && !work.resultJson().isBlank()) {
                AnalysisResult stored = schemaValidator.validate(work.resultJson());
                factValidator.validate(message.analysisId(), snapshot, stored);
                orchestrator.validateStoredResult(snapshot,stored);
                completeForwardTransitions(message.analysisId(), work.status(), now);
                insertReceipt(message, now);
                return WorkerOutcome.COMPLETED_FROM_STORED_RESULT;
            }

            completeForwardTransitions(message.analysisId(), work.status(), now, false);
            execution = orchestrator.execute(message.analysisId(), snapshot);
            AnalysisResult candidate = execution.result();
            AnalysisResult validated = schemaValidator.validate(candidate);
            factValidator.validate(message.analysisId(), snapshot, validated);
            recordSuccess(message.analysisId(), execution);
            String resultJson = serialize(validated);
            runs.insertResult(message.analysisId(), validated.schemaVersion(),
                    validated.recommendation().name(), resultJson, now);
            setStatus(message.analysisId(), AnalysisStatus.VALIDATING, AnalysisStatus.SUCCEEDED, null, now);
            insertReceipt(message, now);
            return WorkerOutcome.COMPLETED;
        } catch (CopilotException exception) {
            if (exception.code() == ErrorCode.CONTEXT_BUDGET_EXCEEDED
                    || exception.code() == ErrorCode.OUTPUT_SCHEMA_INVALID
                    || exception.code() == ErrorCode.FACT_VALIDATION_FAILED
                    || exception.code() == ErrorCode.KNOWLEDGE_UNAVAILABLE) {
                LOG.warn("Analysis validation rejected: code={}", exception.code());
                if (execution != null && execution.usage() != null) {
                    recordRejectedExecution(message.analysisId(), execution);
                }
                markDeterministicFailure(message, exception.code().name(), now);
                return WorkerOutcome.FAILED_GUARDRAIL;
            }
            throw exception;
        } catch (IllegalArgumentException exception) {
            if (execution != null && execution.usage() != null) {
                recordRejectedExecution(message.analysisId(), execution);
            }
            markDeterministicFailure(message, ErrorCode.FACT_VALIDATION_FAILED.name(), now);
            return WorkerOutcome.FAILED_GUARDRAIL;
        }
    }

    private void recordSuccess(UUID analysisId, AnalysisExecution execution) {
        if (execution.usage() == null) {
            return;
        }
        if (usageRecorder == null) {
            throw new IllegalStateException("model usage recorder is not configured");
        }
        usageRecorder.record(analysisId, execution.provider(), execution.modelConfigurationVersion(),
                execution.promptVersion(), execution.usage(), "SUCCESS");
    }

    private void recordRejectedExecution(UUID analysisId, AnalysisExecution execution) {
        if (usageRecorder == null) {
            throw new IllegalStateException("model usage recorder is not configured");
        }
        usageRecorder.record(analysisId, execution.provider(), execution.modelConfigurationVersion(),
                execution.promptVersion(), execution.usage(), "FAILURE");
    }

    private void recordFailure(UUID analysisId, ModelInvocationException exception) {
        if (usageRecorder == null) {
            LOG.warn("model usage recorder is not configured for analysis {}", analysisId);
            return;
        }
        usageRecorder.record(analysisId, exception.provider(), exception.modelConfigurationVersion(),
                exception.promptVersion(), exception.usage(), exception.outcome());
    }

    private FactSnapshot loadSnapshot(AnalysisRunRepository.WorkView work) {
        try {
            var facts = snapshotCodec.decodeAndVerify(
                    work.factsJson().getBytes(StandardCharsets.UTF_8), work.factsSha256());
            return new FactSnapshot(
                    FactSnapshot.CURRENT_SCHEMA_VERSION,
                    facts,
                    facts.capturedAt(),
                    facts.capturedAt().plus(30, java.time.temporal.ChronoUnit.DAYS),
                    work.factsSha256());
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("snapshot checksum verification failed", exception);
        }
    }

    private void completeForwardTransitions(UUID analysisId, AnalysisStatus current, Instant now) {
        completeForwardTransitions(analysisId, current, now, true);
    }

    private void completeForwardTransitions(UUID analysisId, AnalysisStatus current,
                                            Instant now, boolean includeSucceeded) {
        AnalysisStatus[] path = {
                AnalysisStatus.QUEUED,
                AnalysisStatus.RETRIEVAL_READY,
                AnalysisStatus.GENERATING,
                AnalysisStatus.VALIDATING,
                AnalysisStatus.SUCCEEDED
        };
        AnalysisStatus state = current;
        for (AnalysisStatus next : path) {
            if (next == AnalysisStatus.SUCCEEDED && !includeSucceeded) {
                break;
            }
            AnalysisStatus target = stateMachine.transition(state, next);
            if (target != state) {
                setStatus(analysisId, state, target, null, now);
                state = target;
            }
        }
    }

    private void markDeterministicFailure(AnalysisRequestedMessage message, String code, Instant now) {
        AnalysisRunRepository.WorkView work = runs.findForWork(message.analysisId()).orElse(null);
        if (work != null && !stateMachine.isTerminal(work.status())) {
            setStatus(message.analysisId(), work.status(), AnalysisStatus.FAILED, bounded(code), now);
        }
        insertReceipt(message, now);
    }

    private void markRetryExhausted(AnalysisRequestedMessage message) {
        markRetryExhausted(message, RETRYABLE_ERROR);
    }

    private void markRetryExhausted(AnalysisRequestedMessage message, String failureCode) {
        Instant now = now();
        AnalysisRunRepository.WorkView work = runs.findForWork(message.analysisId()).orElse(null);
        if (work != null && !stateMachine.isTerminal(work.status())) {
            setStatus(message.analysisId(), work.status(), AnalysisStatus.FAILED,
                    failureCode, now);
        }
        if (!runs.hasReceipt(message.eventId())) {
            insertReceipt(message, now);
        }
    }

    private void setStatus(UUID analysisId, AnalysisStatus expected, AnalysisStatus next,
                           String failureCode, Instant updatedAt) {
        if (runs.updateStatus(analysisId, expected, next, failureCode, updatedAt) != 1) {
            throw new IllegalStateException("analysis status update lost its row lock");
        }
    }

    private void insertReceipt(AnalysisRequestedMessage message, Instant receivedAt) {
        try {
            runs.insertReceipt(message.eventId(), message.analysisId(), receivedAt);
        } catch (org.springframework.dao.DuplicateKeyException ignored) {
            // At-least-once delivery: another worker already recorded this event.
        }
    }

    private String serialize(AnalysisResult result) {
        try {
            return objectMapper.writeValueAsString(result);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("analysis result cannot be serialized", exception);
        }
    }

    private void publishWithConfirm(String exchange, AnalysisRequestedMessage message, int attempt) {
        CorrelationData correlation = new CorrelationData(message.eventId() + ":" + attempt);
        MessagePostProcessor headers = outbound -> {
            outbound.getMessageProperties().setHeader(ATTEMPT_HEADER, attempt);
            return outbound;
        };
        rabbitTemplate.convertAndSend(exchange, RabbitTopologyConfiguration.ANALYSIS_ROUTING_KEY,
                message, headers, correlation);
        try {
            CorrelationData.Confirm confirm = correlation.getFuture().get(
                    properties.dispatch().publisherConfirmTimeout().toMillis(), TimeUnit.MILLISECONDS);
            if (confirm == null || !confirm.isAck() || correlation.getReturned() != null) {
                throw new IllegalStateException("message broker did not confirm worker republish");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("worker republish was interrupted", exception);
        } catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException exception) {
            throw new IllegalStateException("worker republish was not confirmed", exception);
        }
    }

    private boolean publishMalformedToDlq(Message source) {
        MessageProperties properties = new MessageProperties();
        properties.setHeaders(new java.util.HashMap<>(source.getMessageProperties().getHeaders()));
        properties.setHeader("x-malformed-message", true);
        Message malformed = new Message(source.getBody(), properties);
        CorrelationData correlation = new CorrelationData("malformed:" + source.getMessageProperties().getDeliveryTag());
        try {
            rabbitTemplate.send(RabbitTopologyConfiguration.DLX_EXCHANGE,
                    RabbitTopologyConfiguration.ANALYSIS_ROUTING_KEY, malformed, correlation);
            CorrelationData.Confirm confirm = correlation.getFuture().get(
                    this.properties.dispatch().publisherConfirmTimeout().toMillis(), TimeUnit.MILLISECONDS);
            return confirm != null && confirm.isAck() && correlation.getReturned() == null;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return false;
        } catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException exception) {
            return false;
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private static int attempt(Message message) {
        Object value = message.getMessageProperties().getHeaders().get(ATTEMPT_HEADER);
        if (value instanceof Number number) {
            return Math.max(1, number.intValue());
        }
        if (value instanceof String text) {
            try {
                return Math.max(1, Integer.parseInt(text));
            } catch (NumberFormatException ignored) {
                return 1;
            }
        }
        return 1;
    }

    private Instant now() {
        return Instant.now(clock).truncatedTo(java.time.temporal.ChronoUnit.MICROS);
    }

    private static String bounded(String code) {
        return code == null || code.isBlank() ? ErrorCode.ANALYSIS_WORKER_FAILED.name()
                : code.substring(0, Math.min(64, code.length()));
    }

    public enum WorkerOutcome {
        COMPLETED,
        COMPLETED_FROM_STORED_RESULT,
        FAILED_GUARDRAIL,
        RETRIED,
        DEAD_LETTERED,
        PUBLISH_FAILED,
        NOOP
    }
}
