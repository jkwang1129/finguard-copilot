package com.finguard.copilot.usage;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.finguard.copilot.analysis.persistence.JdbcAnalysisRunRepository;
import com.finguard.copilot.model.port.ModelUsage;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

public final class ModelUsageRecorder {

    private static final Set<String> OUTCOMES = Set.of("SUCCESS", "FAILURE", "TIMEOUT", "RATE_LIMITED");
    private static final String INSERT = """
            INSERT INTO model_usage_ledger (
                analysis_id, provider, model_configuration_version, prompt_version,
                input_tokens, output_tokens, latency_ms, estimated_cost, currency,
                price_table_version, outcome, created_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON DUPLICATE KEY UPDATE
                provider = VALUES(provider),
                model_configuration_version = VALUES(model_configuration_version),
                prompt_version = VALUES(prompt_version),
                input_tokens = VALUES(input_tokens),
                output_tokens = VALUES(output_tokens),
                latency_ms = VALUES(latency_ms),
                estimated_cost = VALUES(estimated_cost),
                currency = VALUES(currency),
                price_table_version = VALUES(price_table_version),
                outcome = VALUES(outcome),
                created_at = VALUES(created_at)
            """;

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final ModelCostCalculator calculator;
    private final ModelCostCalculator.PriceTable priceTable;
    private final Clock clock;
    private final MeterRegistry meterRegistry;
    private final com.finguard.copilot.governance.BudgetReservations budgets;

    public ModelUsageRecorder(JdbcTemplate jdbc, ModelCostCalculator.PriceTable priceTable) {
        this(jdbc, new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource())),
                priceTable, Clock.systemUTC(), null);
    }

    public ModelUsageRecorder(
            JdbcTemplate jdbc,
            TransactionTemplate transactions,
            ModelCostCalculator.PriceTable priceTable,
            Clock clock,
            MeterRegistry meterRegistry
    ) {
        this(jdbc,transactions,priceTable,clock,meterRegistry,null);
    }

    public ModelUsageRecorder(JdbcTemplate jdbc,TransactionTemplate transactions,ModelCostCalculator.PriceTable priceTable,
            Clock clock,MeterRegistry meterRegistry,com.finguard.copilot.governance.BudgetReservations budgets) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc must not be null");
        this.transactions = Objects.requireNonNull(transactions, "transactions must not be null");
        this.calculator = new ModelCostCalculator();
        this.priceTable = Objects.requireNonNull(priceTable, "priceTable must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.meterRegistry = meterRegistry;
        this.budgets=budgets;
    }

    public void record(
            UUID analysisId,
            String provider,
            String modelConfigurationVersion,
            String promptVersion,
            ModelUsage usage,
            String outcome
    ) {
        requireText(provider, 64, "provider");
        requireText(modelConfigurationVersion, 128, "modelConfigurationVersion");
        requireText(promptVersion, 128, "promptVersion");
        if (analysisId == null || usage == null || !OUTCOMES.contains(outcome)) {
            throw new IllegalArgumentException("model usage record is invalid");
        }
        if(budgets!=null && budgets.hasReservation(analysisId)) {
            recordGoverned(analysisId,provider,modelConfigurationVersion,promptVersion,usage,outcome);
            return;
        }
        ModelCostCalculator.MoneyAmount cost = calculator.estimate(usage, priceTable);
        Instant createdAt = clock.instant();
        transactions.executeWithoutResult(ignored -> jdbc.update(
                INSERT,
                JdbcAnalysisRunRepository.uuidBytes(analysisId),
                provider,
                modelConfigurationVersion,
                promptVersion,
                usage.inputTokens(),
                usage.outputTokens(),
                usage.latency().toMillis(),
                cost.amount(),
                cost.currency(),
                priceTable.version(),
                outcome,
                Timestamp.from(createdAt)));
        recordMetrics(provider, modelConfigurationVersion, outcome, usage, cost);
    }

    private void recordGoverned(UUID id,String provider,String modelVersion,String prompt,ModelUsage usage,String outcome) {
        var cost=transactions.execute(ignored->{
            var frozen=budgets.frozen(id);
            if(!frozen.provider().equals(provider) || !frozen.manifest().path("model").path("version").asText().equals(modelVersion) ||
                    !frozen.manifest().path("prompt").path("version").asText().equals(prompt))
                throw com.finguard.copilot.governance.RunManifestService.rejected("usage metadata does not match frozen run");
            if(!budgets.settle(id,usage)) return new GovernedAccounting(null,false,false);
            boolean newLedger=jdbc.queryForList("SELECT analysis_id FROM model_usage_ledger WHERE analysis_id=? FOR UPDATE",
                    JdbcAnalysisRunRepository.uuidBytes(id)).isEmpty();
            var amount=com.finguard.copilot.governance.BudgetReservations.cost(frozen.price(),usage.inputTokens(),usage.outputTokens());
            String insert=INSERT.substring(0,INSERT.indexOf("ON DUPLICATE KEY UPDATE"))+"ON DUPLICATE KEY UPDATE analysis_id=analysis_id";
            jdbc.update(insert,JdbcAnalysisRunRepository.uuidBytes(id),provider,modelVersion,prompt,usage.inputTokens(),usage.outputTokens(),
                    usage.latency().toMillis(),amount,frozen.price().currency(),frozen.price().version(),outcome,Timestamp.from(clock.instant()));
            Integer matches=jdbc.queryForObject("""
                    SELECT COUNT(*) FROM model_usage_ledger WHERE analysis_id=? AND provider=? AND model_configuration_version=?
                    AND prompt_version=? AND input_tokens=? AND output_tokens=? AND estimated_cost=? AND currency=? AND price_table_version=? AND outcome=?
                    """,Integer.class,JdbcAnalysisRunRepository.uuidBytes(id),provider,modelVersion,prompt,usage.inputTokens(),usage.outputTokens(),
                    amount,frozen.price().currency(),frozen.price().version(),outcome);
            if(matches!=1) throw com.finguard.copilot.governance.RunManifestService.rejected("immutable usage ledger conflict");
            return new GovernedAccounting(new ModelCostCalculator.MoneyAmount(amount,frozen.price().currency()),true,newLedger);
        });
        if(cost==null || !cost.verified()) {
            if("SUCCESS".equals(outcome)) throw new com.finguard.copilot.common.error.CopilotException(
                    com.finguard.copilot.common.error.ErrorCode.SERVICE_UNAVAILABLE,"model accounting requires reconciliation");
            return;
        }
        if(cost.firstWrite()) recordMetrics(provider,modelVersion,outcome,usage,cost.amount());
    }

    private record GovernedAccounting(ModelCostCalculator.MoneyAmount amount,boolean verified,boolean firstWrite) {}

    private void recordMetrics(
            String provider,
            String modelConfigurationVersion,
            String outcome,
            ModelUsage usage,
            ModelCostCalculator.MoneyAmount cost
    ) {
        if (meterRegistry == null) {
            return;
        }
        try {
        provider=com.finguard.copilot.observability.TelemetryVocabulary.provider(provider);
        outcome=com.finguard.copilot.observability.TelemetryVocabulary.outcome(outcome);
        Counter.builder("copilot.model.usage.records")
                .tag("provider", provider)
                .tag("outcome", outcome)
                .register(meterRegistry).increment();
        Counter.builder("copilot.model.input.tokens")
                .tag("provider", provider)
                .tag("outcome", outcome)
                .register(meterRegistry).increment(usage.inputTokens());
        Counter.builder("copilot.model.output.tokens")
                .tag("provider", provider)
                .tag("outcome", outcome)
                .register(meterRegistry).increment(usage.outputTokens());
        Timer.builder("copilot.model.latency")
                .tag("provider", provider)
                .tag("outcome", outcome)
                .register(meterRegistry).record(usage.latency());
        DistributionSummary.builder("copilot.model.estimated.cost")
                .tag("provider", provider)
                .tag("outcome", outcome)
                .register(meterRegistry).record(cost.amount().doubleValue());
        if (!"SUCCESS".equals(outcome)) {
            Counter.builder("copilot.model.failures")
                    .tag("provider", provider)
                    .tag("failure_category", outcome.toLowerCase())
                    .register(meterRegistry).increment();
        }
        } catch(RuntimeException ignored) {
            // The ledger has already committed; a meter failure must not change the business outcome.
        }
    }

    private void requireText(String value, int maxLength, String name) {
        if (value == null || value.isBlank() || value.length() > maxLength) {
            throw new IllegalArgumentException(name + " is invalid");
        }
    }
}
