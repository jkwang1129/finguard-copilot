package com.finguard.copilot.common.error;

public enum ErrorCode {
    VALIDATION_FAILED("Request validation failed"),
    INVALID_REQUEST("Invalid request"),
    RESOURCE_NOT_FOUND("Resource not found"),
    CONFLICT("Request conflict"),
    INTERNAL_ERROR("Internal server error");

    private final String title;

    ErrorCode(String title) {
        this.title = title;
    }

    public String title() {
        return title;
    }
}
