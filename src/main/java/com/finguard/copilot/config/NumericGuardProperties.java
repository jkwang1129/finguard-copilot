package com.finguard.copilot.config;

import java.util.List;

import com.finguard.copilot.guardrail.NumericThresholdGuard;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("finguard.copilot.numeric-guard")
public record NumericGuardProperties(List<NumericThresholdGuard.Rule> rules) {
    public NumericGuardProperties {
        rules = rules == null ? List.of() : List.copyOf(rules);
    }
}
