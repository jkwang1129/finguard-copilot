package com.finguard.copilot.analysis.application;

import java.util.UUID;

import com.finguard.copilot.analysis.domain.AnalysisStatus;
import com.finguard.copilot.analysis.persistence.AnalysisRunRepository;
import com.finguard.copilot.analysis.persistence.ReviewFeedbackRepository;
import com.finguard.copilot.common.error.CopilotException;
import com.finguard.copilot.common.error.ErrorCode;
import com.finguard.copilot.coreclient.port.CoreClient;
import com.finguard.copilot.coreclient.port.CurrentUser;
import org.springframework.stereotype.Service;

@Service
public class SubmitFeedbackService {
    private final CoreClient core;
    private final AnalysisRunRepository runs;
    private final ReviewFeedbackRepository feedback;

    public SubmitFeedbackService(CoreClient core, AnalysisRunRepository runs,
                                 ReviewFeedbackRepository feedback) {
        this.core = core;
        this.runs = runs;
        this.feedback = feedback;
    }

    public FeedbackResponse submit(UUID analysisId, String verdict, String token) {
        if (analysisId == null || !("AGREE".equals(verdict) || "DISAGREE".equals(verdict)
                || "UNSURE".equals(verdict))) {
            throw new CopilotException(ErrorCode.INVALID_REQUEST, "Invalid feedback verdict");
        }
        CurrentUser user = core.currentUser(token);
        if (!user.roles().contains("REVIEWER")) {
            throw new CopilotException(ErrorCode.FORBIDDEN, "Reviewer access is required");
        }
        var run = runs.findByIdAndReviewer(analysisId, user.username())
                .orElseThrow(() -> new CopilotException(ErrorCode.RESOURCE_NOT_FOUND,
                        "Analysis run was not found"));
        if (run.status() != AnalysisStatus.SUCCEEDED) {
            throw new CopilotException(ErrorCode.CONFLICT, "Only completed analyses accept feedback");
        }
        return new FeedbackResponse(analysisId, feedback.submit(analysisId, user.username(), verdict));
    }

    public record FeedbackResponse(UUID analysisId, String verdict) {}
}
