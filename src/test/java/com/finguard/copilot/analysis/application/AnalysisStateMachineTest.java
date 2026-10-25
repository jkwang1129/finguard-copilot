package com.finguard.copilot.analysis.application;

import com.finguard.copilot.analysis.domain.AnalysisStatus;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AnalysisStateMachineTest {

    private final AnalysisStateMachine stateMachine = new AnalysisStateMachine();

    @Test
    void acceptsOnlyTheForwardAnalysisPath() {
        assertThat(stateMachine.transition(AnalysisStatus.CONTEXT_READY, AnalysisStatus.QUEUED))
                .isEqualTo(AnalysisStatus.QUEUED);
        assertThat(stateMachine.transition(AnalysisStatus.QUEUED, AnalysisStatus.RETRIEVAL_READY))
                .isEqualTo(AnalysisStatus.RETRIEVAL_READY);
        assertThat(stateMachine.transition(AnalysisStatus.RETRIEVAL_READY, AnalysisStatus.GENERATING))
                .isEqualTo(AnalysisStatus.GENERATING);
        assertThat(stateMachine.transition(AnalysisStatus.GENERATING, AnalysisStatus.VALIDATING))
                .isEqualTo(AnalysisStatus.VALIDATING);
        assertThat(stateMachine.transition(AnalysisStatus.VALIDATING, AnalysisStatus.SUCCEEDED))
                .isEqualTo(AnalysisStatus.SUCCEEDED);
    }

    @Test
    void allowsFailureFromAnyNonTerminalState() {
        for (AnalysisStatus status : new AnalysisStatus[] {
                AnalysisStatus.CONTEXT_READY,
                AnalysisStatus.QUEUED,
                AnalysisStatus.RETRIEVAL_READY,
                AnalysisStatus.GENERATING,
                AnalysisStatus.VALIDATING
        }) {
            assertThat(stateMachine.transition(status, AnalysisStatus.FAILED))
                    .isEqualTo(AnalysisStatus.FAILED);
        }
    }

    @Test
    void makesDuplicateAndStaleReceiptsNoOpsAndKeepsTerminalsTerminal() {
        assertThat(stateMachine.transition(AnalysisStatus.GENERATING, AnalysisStatus.GENERATING))
                .isEqualTo(AnalysisStatus.GENERATING);
        assertThat(stateMachine.transition(AnalysisStatus.GENERATING, AnalysisStatus.QUEUED))
                .isEqualTo(AnalysisStatus.GENERATING);
        assertThat(stateMachine.transition(AnalysisStatus.SUCCEEDED, AnalysisStatus.SUCCEEDED))
                .isEqualTo(AnalysisStatus.SUCCEEDED);
        assertThat(stateMachine.transition(AnalysisStatus.FAILED, AnalysisStatus.FAILED))
                .isEqualTo(AnalysisStatus.FAILED);
    }

    @Test
    void rejectsSkippedAndTerminalTransitions() {
        assertThatThrownBy(() -> stateMachine.transition(
                AnalysisStatus.CONTEXT_READY, AnalysisStatus.GENERATING))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> stateMachine.transition(
                AnalysisStatus.SUCCEEDED, AnalysisStatus.FAILED))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> stateMachine.transition(
                AnalysisStatus.FAILED, AnalysisStatus.SUCCEEDED))
                .isInstanceOf(IllegalStateException.class);
    }
}
