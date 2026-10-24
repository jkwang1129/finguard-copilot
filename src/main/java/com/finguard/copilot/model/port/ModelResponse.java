package com.finguard.copilot.model.port;

import java.time.Duration;

public record ModelResponse(
        String modelId,
        String content,
        int inputTokens,
        int outputTokens,
        String structuredContent,
        ModelUsage usage,
        Duration latency,
        String provider
) {

    public ModelResponse(String modelId, String content, int inputTokens, int outputTokens) {
        this(modelId, content, inputTokens, outputTokens, content,
                new ModelUsage(inputTokens, outputTokens, Duration.ZERO), Duration.ZERO, "stub");
    }

    public ModelResponse {
        requireText(modelId, "modelId");
        requireText(content, "content");
        requireText(structuredContent, "structuredContent");
        if (inputTokens < 0 || outputTokens < 0) {
            throw new IllegalArgumentException("token counts must not be negative");
        }
        if (usage == null || latency == null || latency.isNegative()) {
            throw new IllegalArgumentException("usage and latency must be valid");
        }
        requireText(provider, "provider");
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
