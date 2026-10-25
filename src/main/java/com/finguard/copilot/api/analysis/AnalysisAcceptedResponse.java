package com.finguard.copilot.api.analysis;

import java.util.UUID;

import com.finguard.copilot.analysis.domain.AnalysisStatus;

public record AnalysisAcceptedResponse(
        UUID analysisId,
        AnalysisStatus status,
        boolean replayed
) {

    public AnalysisAcceptedResponse {
        if (analysisId == null || status == null) {
            throw new IllegalArgumentException("accepted response fields must not be null");
        }
    }
}
