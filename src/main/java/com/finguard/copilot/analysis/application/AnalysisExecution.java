package com.finguard.copilot.analysis.application;

import java.util.Objects;

import com.finguard.copilot.analysis.domain.AnalysisResult;
import com.finguard.copilot.model.port.ModelUsage;

/**
 * The validated result of one analysis execution and, when a model was used,
 * the non-sensitive metadata required for usage accounting.
 */
public record AnalysisExecution(
        AnalysisResult result,
        String provider,
        String modelConfigurationVersion,
        String promptVersion,
        ModelUsage usage
) {

    public AnalysisExecution {
        Objects.requireNonNull(result, "result must not be null");
        boolean hasUsage = usage != null;
        if (hasUsage) {
            requireText(provider, "provider");
            requireText(modelConfigurationVersion, "modelConfigurationVersion");
            requireText(promptVersion, "promptVersion");
        } else if (provider != null || modelConfigurationVersion != null || promptVersion != null) {
            throw new IllegalArgumentException("model metadata requires usage");
        }
    }

    public static AnalysisExecution withoutModel(AnalysisResult result) {
        return new AnalysisExecution(result, null, null, null, null);
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
