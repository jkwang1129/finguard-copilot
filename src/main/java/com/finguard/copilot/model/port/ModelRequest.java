package com.finguard.copilot.model.port;

public record ModelRequest(String requestId, String instructions, String context) {

    public ModelRequest {
        requireText(requestId, "requestId");
        requireText(instructions, "instructions");
        requireText(context, "context");
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
