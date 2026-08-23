package com.finguard.copilot.model.port;

public record ModelResponse(String modelId, String content, int inputTokens, int outputTokens) {

    public ModelResponse {
        requireText(modelId, "modelId");
        requireText(content, "content");
        if (inputTokens < 0 || outputTokens < 0) {
            throw new IllegalArgumentException("token counts must not be negative");
        }
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
