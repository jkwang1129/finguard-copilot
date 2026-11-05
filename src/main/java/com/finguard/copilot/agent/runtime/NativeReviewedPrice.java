package com.finguard.copilot.agent.runtime;
import java.math.BigDecimal;
import java.time.*;
import com.finguard.copilot.config.CopilotProperties;
import com.finguard.copilot.governance.GovernanceProperties;
import com.finguard.copilot.common.error.*;
/** Short-lived reviewed peak/cache-miss CNY snapshot; future prices require a new reviewed version. */
public final class NativeReviewedPrice {
    public static final String VERSION="deepseek-flash-native-20261104-peak-miss-cny-v1";
    public static final Instant FROM=Instant.parse("2026-11-04T00:00:00Z"),UNTIL=Instant.parse("2026-11-05T00:00:00Z");
    public record Window(Instant from,Instant until){}
    public static Window validate(CopilotProperties.Model model,GovernanceProperties governance,Clock clock){
        Instant from=governance.priceValidFrom().isAfter(FROM)?governance.priceValidFrom():FROM;
        Instant until=governance.priceValidUntil().isBefore(UNTIL)?governance.priceValidUntil():UNTIL;
        if(!VERSION.equals(model.priceTableVersion()) || !"CNY".equals(model.currency())
            || model.inputPricePerMillion().compareTo(new BigDecimal("2"))!=0 || model.outputPricePerMillion().compareTo(new BigDecimal("8"))!=0
            || clock.instant().isBefore(from) || !clock.instant().isBefore(until))throw new CopilotException(ErrorCode.MODEL_CONFIGURATION_INVALID,"Agent native reviewed price invalid or expired");
        return new Window(from,until);
    }
    private NativeReviewedPrice(){}
}
