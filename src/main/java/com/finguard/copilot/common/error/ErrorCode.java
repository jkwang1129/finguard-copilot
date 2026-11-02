package com.finguard.copilot.common.error;

public enum ErrorCode {
    UNAUTHENTICATED("Authentication is required"),
    FORBIDDEN("Access is forbidden"),
    VALIDATION_FAILED("Request validation failed"),
    INVALID_REQUEST("Invalid request"),
    RESOURCE_NOT_FOUND("Resource not found"),
    CONFLICT("Request conflict"),
    CONTEXT_BUDGET_EXCEEDED("Context budget exceeded"),
    OUTPUT_SCHEMA_INVALID("Analysis output schema is invalid"),
    FACT_VALIDATION_FAILED("Analysis facts failed validation"),
    KNOWLEDGE_UNAVAILABLE("Published knowledge is unavailable"),
    MODEL_UNAVAILABLE("Model provider is unavailable"),
    MODEL_TIMEOUT("Model provider timed out"),
    MODEL_RATE_LIMITED("Model provider rate limit reached"),
    MODEL_OUTPUT_INVALID("Model output is invalid"),
    MODEL_CONFIGURATION_INVALID("Model call configuration failed verification"),
    MODEL_BUDGET_EXCEEDED("Model call budget exhausted"),
    MODEL_CAPACITY_UNAVAILABLE("Model capacity permission unavailable"),
    BROKER_UNAVAILABLE("Message broker is unavailable"),
    ANALYSIS_RETRY_EXHAUSTED("Analysis retries were exhausted"),
    ANALYSIS_WORKER_FAILED("Analysis worker failed"),
    SERVICE_UNAVAILABLE("Service unavailable"),
    INTERNAL_ERROR("Internal server error");

    private final String title;

    ErrorCode(String title) {
        this.title = title;
    }

    public String title() {
        return title;
    }
}
