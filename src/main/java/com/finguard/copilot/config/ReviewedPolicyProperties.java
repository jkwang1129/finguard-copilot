package com.finguard.copilot.config;

import java.util.List;
import com.finguard.copilot.guardrail.ReviewedPolicyGuard;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("finguard.copilot.reviewed-policy")
public record ReviewedPolicyProperties(List<ReviewedPolicyGuard.PolicySet> sets) {
    public ReviewedPolicyProperties {sets=sets==null?List.of():List.copyOf(sets);}
}
