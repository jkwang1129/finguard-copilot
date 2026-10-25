package com.finguard.copilot.api.analysis;

import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import com.finguard.copilot.analysis.domain.AnalysisStatus;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record AnalysisResponse(
        UUID analysisId,
        AnalysisStatus status,
        JsonNode result,
        String failureCode
) {

    public AnalysisResponse {
        if (analysisId == null || status == null) {
            throw new IllegalArgumentException("analysis response fields must not be null");
        }
        if (status == AnalysisStatus.SUCCEEDED && result == null) {
            throw new IllegalArgumentException("succeeded response must contain a result");
        }
        if (status == AnalysisStatus.FAILED
                && (failureCode == null || failureCode.isBlank() || failureCode.length() > 64)) {
            throw new IllegalArgumentException("failed response must contain a bounded failure code");
        }
        if (status != AnalysisStatus.SUCCEEDED && result != null) {
            throw new IllegalArgumentException("non-succeeded response must not contain a result");
        }
        if (status != AnalysisStatus.FAILED && failureCode != null) {
            throw new IllegalArgumentException("non-failed response must not contain a failure code");
        }
    }
}
