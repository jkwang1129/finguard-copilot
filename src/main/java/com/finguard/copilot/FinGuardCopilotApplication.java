package com.finguard.copilot;

import com.finguard.copilot.analysis.application.AnalysisOrchestrator;
import com.finguard.copilot.analysis.application.DeterministicAnalysisOrchestrator;
import com.finguard.copilot.config.CopilotProperties;
import com.finguard.copilot.usage.ModelCostCalculator;
import com.finguard.copilot.usage.ModelUsageRecorder;
import com.finguard.copilot.knowledge.retrieval.KnowledgeRetrievalService;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.context.annotation.Profile;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import com.finguard.copilot.guardrail.AnalysisOutputSchemaValidator;
import com.finguard.copilot.guardrail.FactConsistencyValidator;
import org.springframework.context.annotation.Bean;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
public class FinGuardCopilotApplication {

    @Bean
    @Profile("!real-model & !real-qwen & !real-deepseek & !real-zhipu")
    AnalysisOrchestrator analysisOrchestrator(ObjectProvider<KnowledgeRetrievalService> retrieval) {
        return new DeterministicAnalysisOrchestrator(
                new AnalysisOutputSchemaValidator(), new FactConsistencyValidator(),
                retrieval.getIfAvailable());
    }

    @Bean
    com.finguard.copilot.governance.BudgetReservations budgetReservations(JdbcTemplate jdbc,
            TransactionTemplate transactions,com.finguard.copilot.usage.ModelCallReservations calls) {
        return new com.finguard.copilot.governance.BudgetReservations(jdbc,transactions,calls,java.time.Clock.systemUTC());
    }

    @Bean
    ModelUsageRecorder modelUsageRecorder(
            JdbcTemplate jdbc,
            CopilotProperties properties,
            MeterRegistry meterRegistry,
            com.finguard.copilot.governance.BudgetReservations budgets
    ) {
        CopilotProperties.Model model = properties.model();
        return new ModelUsageRecorder(jdbc,
                new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource())),
                new ModelCostCalculator.PriceTable(
                        model.priceTableVersion(), model.currency(), model.inputPricePerMillion(),
                        model.outputPricePerMillion()),
                java.time.Clock.systemUTC(), meterRegistry,budgets);
    }

    @Bean
    AnalysisOutputSchemaValidator analysisOutputSchemaValidator() {
        return new AnalysisOutputSchemaValidator();
    }

    @Bean
    FactConsistencyValidator factConsistencyValidator() {
        return new FactConsistencyValidator();
    }

    public static void main(String[] args) {
        SpringApplication.run(FinGuardCopilotApplication.class, args);
    }
}
