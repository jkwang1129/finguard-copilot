package com.finguard.copilot.analysis.application;

import java.util.UUID;

import com.finguard.copilot.analysis.domain.AnalysisResult;
import com.finguard.copilot.context.FactSnapshot;

public interface AnalysisOrchestrator {
    default boolean managesModelReservation() {return false;}
    default void modelCallOutcomeUnknown(UUID analysisId) {}
    default void modelCallOutcomeUnknown(UUID analysisId,com.finguard.copilot.model.port.ModelUsage usage) {modelCallOutcomeUnknown(analysisId);}

    default boolean requiresModelCall() {
        return false;
    }

    AnalysisResult analyze(UUID analysisId, FactSnapshot snapshot);

    default void validateStoredResult(FactSnapshot snapshot, AnalysisResult result) {
        if(result.recommendation()!=com.finguard.copilot.analysis.domain.AnalysisRecommendation.INSUFFICIENT_EVIDENCE)
            throw new com.finguard.copilot.common.error.CopilotException(
                    com.finguard.copilot.common.error.ErrorCode.FACT_VALIDATION_FAILED,
                    "stored directional result requires reviewed policy authorization");
    }

    default AnalysisExecution execute(UUID analysisId, FactSnapshot snapshot) {
        return AnalysisExecution.withoutModel(analyze(analysisId, snapshot));
    }
}
