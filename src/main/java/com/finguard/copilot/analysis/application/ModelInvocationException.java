package com.finguard.copilot.analysis.application;

import java.util.Objects;
import java.util.Set;

import com.finguard.copilot.common.error.ErrorCode;
import com.finguard.copilot.model.port.ModelUsage;

/**
 * A sanitized model failure carrying only metadata needed for retry and usage accounting.
 */
public final class ModelInvocationException extends RuntimeException {

    private final ErrorCode errorCode;
    private final String provider;
    private final String modelConfigurationVersion;
    private final String promptVersion;
    private final ModelUsage usage;
    private final String outcome;
    private final ModelValidationStage validationStage;

    public ModelInvocationException(
            ErrorCode errorCode,
            String provider,
            String modelConfigurationVersion,
            String promptVersion,
            ModelUsage usage,
            String outcome
    ) {
        this(errorCode, provider, modelConfigurationVersion, promptVersion, usage, outcome, ModelValidationStage.UNKNOWN);
    }

    public ModelInvocationException(
            ErrorCode errorCode,
            String provider,
            String modelConfigurationVersion,
            String promptVersion,
            ModelUsage usage,
            String outcome,
            ModelValidationStage validationStage
    ) {
        super("model invocation failed: " + Objects.requireNonNull(errorCode, "errorCode must not be null"));
        this.errorCode = errorCode;
        this.provider = requireText(provider, "provider");
        this.modelConfigurationVersion = requireText(modelConfigurationVersion, "modelConfigurationVersion");
        this.promptVersion = requireText(promptVersion, "promptVersion");
        this.usage = Objects.requireNonNull(usage, "usage must not be null");
        if (!Set.of("SUCCESS", "FAILURE", "TIMEOUT", "RATE_LIMITED").contains(outcome)) {
            throw new IllegalArgumentException("outcome is invalid");
        }
        this.outcome = outcome;
        this.validationStage = Objects.requireNonNull(validationStage, "validationStage must not be null");
    }

    public ErrorCode errorCode() {
        return errorCode;
    }

    public String provider() {
        return provider;
    }

    public String modelConfigurationVersion() {
        return modelConfigurationVersion;
    }

    public String promptVersion() {
        return promptVersion;
    }

    public ModelUsage usage() {
        return usage;
    }

    public String outcome() {
        return outcome;
    }

    public ModelValidationStage validationStage() {
        return validationStage;
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
