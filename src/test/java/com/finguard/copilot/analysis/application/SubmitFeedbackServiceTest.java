package com.finguard.copilot.analysis.application;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.finguard.copilot.analysis.domain.AnalysisStatus;
import com.finguard.copilot.analysis.persistence.AnalysisRunRepository;
import com.finguard.copilot.analysis.persistence.AnalysisRunRepository.AnalysisView;
import com.finguard.copilot.analysis.persistence.ReviewFeedbackRepository;
import com.finguard.copilot.common.error.CopilotException;
import com.finguard.copilot.common.error.ErrorCode;
import com.finguard.copilot.coreclient.port.CoreClient;
import com.finguard.copilot.coreclient.port.CurrentUser;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class SubmitFeedbackServiceTest {
    private final CoreClient core = mock(CoreClient.class);
    private final AnalysisRunRepository runs = mock(AnalysisRunRepository.class);
    private final ReviewFeedbackRepository feedback = mock(ReviewFeedbackRepository.class);
    private final SubmitFeedbackService service = new SubmitFeedbackService(core, runs, feedback);
    private final UUID id = UUID.randomUUID();

    @Test void permitsOwnedSucceededRunAndIdempotentReplay() {
        when(core.currentUser("token")).thenReturn(new CurrentUser("alice", Set.of("REVIEWER")));
        when(runs.findByIdAndReviewer(id, "alice")).thenReturn(Optional.of(
                new AnalysisView(id, AnalysisStatus.SUCCEEDED, null, "{}")));
        when(feedback.submit(id, "alice", "AGREE")).thenReturn("AGREE");
        assertThat(service.submit(id, "AGREE", "token").verdict()).isEqualTo("AGREE");
        verify(feedback).submit(id, "alice", "AGREE");
    }

    @Test void rejectsCrossReviewerAndMissingRunWithoutWriting() {
        when(core.currentUser("token")).thenReturn(new CurrentUser("bob", Set.of("REVIEWER")));
        assertThatThrownBy(() -> service.submit(id, "AGREE", "token"))
                .isInstanceOf(CopilotException.class)
                .satisfies(ex -> assertThat(((CopilotException) ex).code()).isEqualTo(ErrorCode.RESOURCE_NOT_FOUND));
        verifyNoInteractions(feedback);
    }

    @Test void rejectsNonReviewerAndPendingRun() {
        when(core.currentUser("token")).thenReturn(new CurrentUser("alice", Set.of("VIEWER")));
        assertThatThrownBy(() -> service.submit(id, "AGREE", "token"))
                .satisfies(ex -> assertThat(((CopilotException) ex).code()).isEqualTo(ErrorCode.FORBIDDEN));
        when(core.currentUser("token")).thenReturn(new CurrentUser("alice", Set.of("REVIEWER")));
        when(runs.findByIdAndReviewer(id, "alice")).thenReturn(Optional.of(
                new AnalysisView(id, AnalysisStatus.QUEUED, null, null)));
        assertThatThrownBy(() -> service.submit(id, "AGREE", "token"))
                .satisfies(ex -> assertThat(((CopilotException) ex).code()).isEqualTo(ErrorCode.CONFLICT));
        verifyNoInteractions(feedback);
    }
}
