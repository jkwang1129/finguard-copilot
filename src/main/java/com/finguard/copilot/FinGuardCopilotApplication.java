package com.finguard.copilot;

import com.finguard.copilot.analysis.application.AnalysisOrchestrator;
import com.finguard.copilot.analysis.application.DeterministicAnalysisOrchestrator;
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
    AnalysisOrchestrator analysisOrchestrator() {
        return new DeterministicAnalysisOrchestrator();
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
