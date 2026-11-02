package com.finguard.copilot.governance;

import java.math.BigDecimal;
import java.time.Instant;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("finguard.copilot.governance")
public record GovernanceProperties(BigDecimal maxCallCny, Instant priceValidFrom, Instant priceValidUntil,
        BigDecimal maxReviewerDailyCny,BigDecimal maxProviderDailyCny,BigDecimal maxGlobalDailyCny,boolean fixtureMode) {
    public GovernanceProperties {
        maxCallCny=maxCallCny==null?new BigDecimal("0.10"):maxCallCny;
        priceValidFrom=priceValidFrom==null?Instant.parse("2026-11-02T00:00:00Z"):priceValidFrom;
        priceValidUntil=priceValidUntil==null?Instant.parse("2026-12-02T00:00:00Z"):priceValidUntil;
        maxReviewerDailyCny=maxReviewerDailyCny==null?BigDecimal.ONE:maxReviewerDailyCny;
        maxProviderDailyCny=maxProviderDailyCny==null?BigDecimal.TEN:maxProviderDailyCny;
        maxGlobalDailyCny=maxGlobalDailyCny==null?BigDecimal.TEN:maxGlobalDailyCny;
        if(maxCallCny.signum()<=0 || maxCallCny.scale()>8 || !priceValidUntil.isAfter(priceValidFrom))
            throw new IllegalArgumentException("invalid governance budget or price interval");
        for(var limit:java.util.List.of(maxReviewerDailyCny,maxProviderDailyCny,maxGlobalDailyCny))
            if(limit.signum()<=0 || limit.scale()>8 || limit.precision()>20) throw new IllegalArgumentException("invalid daily budget limit");
    }
    public static GovernanceProperties defaults() { return new GovernanceProperties(null,null,null,null,null,null,false); }
}
