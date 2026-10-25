package com.finguard.copilot.analysis.application;

import java.util.EnumSet;
import java.util.Set;

import com.finguard.copilot.analysis.domain.AnalysisStatus;

public final class AnalysisStateMachine {

    private static final Set<AnalysisStatus> NON_TERMINAL = EnumSet.of(
            AnalysisStatus.CONTEXT_READY,
            AnalysisStatus.QUEUED,
            AnalysisStatus.RETRIEVAL_READY,
            AnalysisStatus.GENERATING,
            AnalysisStatus.VALIDATING);

    public AnalysisStatus transition(AnalysisStatus current, AnalysisStatus requested) {
        if (current == null || requested == null) {
            throw new IllegalArgumentException("analysis statuses must not be null");
        }
        if (current == requested) {
            return current;
        }
        if (isTerminal(current)) {
            throw new IllegalStateException("terminal analysis status cannot transition");
        }
        if (requested.ordinal() < current.ordinal()) {
            return current;
        }
        if (requested == AnalysisStatus.FAILED) {
            return requested;
        }
        if (next(current) != requested) {
            throw new IllegalStateException(
                    "analysis status transition is not allowed: " + current + " -> " + requested);
        }
        return requested;
    }

    public boolean isTerminal(AnalysisStatus status) {
        return status == AnalysisStatus.SUCCEEDED || status == AnalysisStatus.FAILED;
    }

    private static AnalysisStatus next(AnalysisStatus status) {
        return switch (status) {
            case CONTEXT_READY -> AnalysisStatus.QUEUED;
            case QUEUED -> AnalysisStatus.RETRIEVAL_READY;
            case RETRIEVAL_READY -> AnalysisStatus.GENERATING;
            case GENERATING -> AnalysisStatus.VALIDATING;
            case VALIDATING -> AnalysisStatus.SUCCEEDED;
            case SUCCEEDED, FAILED -> throw new IllegalStateException("terminal status has no next state");
        };
    }
}
