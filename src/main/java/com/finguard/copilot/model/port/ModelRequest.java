package com.finguard.copilot.model.port;

public record ModelRequest(
        String requestId,
        String systemInstruction,
        String context,
        String outputSchemaJson,
        int maxOutputTokens,
        double temperature,
        String promptVersion,
        String modelConfigurationVersion
) {

    public ModelRequest(String requestId, String instructions, String context) {
        this(requestId, instructions, context, "{}", 2000, 0d,
                "unversioned", "unversioned");
    }

    public ModelRequest {
        requireText(requestId, "requestId");
        requireText(systemInstruction, "systemInstruction");
        requireText(context, "context");
        requireText(outputSchemaJson, "outputSchemaJson");
        requireText(promptVersion, "promptVersion");
        requireText(modelConfigurationVersion, "modelConfigurationVersion");
        if (maxOutputTokens < 1) {
            throw new IllegalArgumentException("maxOutputTokens must be positive");
        }
        if (!Double.isFinite(temperature) || temperature < 0 || temperature > 2) {
            throw new IllegalArgumentException("temperature must be between 0 and 2");
        }
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
