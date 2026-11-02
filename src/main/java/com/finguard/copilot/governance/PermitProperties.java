package com.finguard.copilot.governance;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("finguard.copilot.governance.permits")
public record PermitProperties(@DefaultValue("2") int providerConcurrency,@DefaultValue("10") int reviewerPerMinute) {
    public PermitProperties {
        if(providerConcurrency<1 || providerConcurrency>100 || reviewerPerMinute<1 || reviewerPerMinute>10000)
            throw new IllegalArgumentException("invalid model capacity settings");
    }
}
