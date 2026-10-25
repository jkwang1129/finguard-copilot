package com.finguard.copilot.analysis.application;

import java.util.UUID;

import com.finguard.copilot.analysis.domain.AnalysisResult;
import com.finguard.copilot.context.FactSnapshot;

public interface AnalysisOrchestrator {

    default boolean requiresModelCall() {
        return false;
    }

    AnalysisResult analyze(UUID analysisId, FactSnapshot snapshot);

    default AnalysisExecution execute(UUID analysisId, FactSnapshot snapshot) {
        return AnalysisExecution.withoutModel(analyze(analysisId, snapshot));
    }
}
