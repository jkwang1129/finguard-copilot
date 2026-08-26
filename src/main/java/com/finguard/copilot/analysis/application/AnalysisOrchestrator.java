package com.finguard.copilot.analysis.application;

import java.util.UUID;

import com.finguard.copilot.analysis.domain.AnalysisResult;
import com.finguard.copilot.context.FactSnapshot;

public interface AnalysisOrchestrator {

    AnalysisResult analyze(UUID analysisId, FactSnapshot snapshot);
}
