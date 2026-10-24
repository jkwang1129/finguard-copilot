package com.finguard.copilot.model.port;

import java.time.Duration;

public record ModelUsage(int inputTokens, int outputTokens, Duration latency) {

    public ModelUsage {
        if (inputTokens < 0 || outputTokens < 0 || latency == null || latency.isNegative()) {
            throw new IllegalArgumentException("invalid model usage");
        }
    }
}
