package com.finguard.copilot.api.analysis;

import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.finguard.copilot.analysis.application.SubmitFeedbackService;
import com.finguard.copilot.analysis.application.SubmitFeedbackService.FeedbackResponse;
import com.finguard.copilot.common.error.CopilotException;
import com.finguard.copilot.common.error.ErrorCode;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/copilot/review-analyses/{analysisId}/feedback")
public class ReviewFeedbackController {
    private final SubmitFeedbackService service;

    public ReviewFeedbackController(SubmitFeedbackService service) {
        this.service = service;
    }

    @PutMapping
    public FeedbackResponse submit(
            @PathVariable UUID analysisId,
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @RequestBody JsonNode body) {
        if (authorization == null || authorization.length() <= 7
                || !authorization.regionMatches(true, 0, "Bearer ", 0, 7)) {
            throw new CopilotException(ErrorCode.UNAUTHENTICATED, "Authentication is required");
        }
        if (body == null || !body.isObject() || body.size() != 1
                || !body.has("verdict") || !body.get("verdict").isTextual()) {
            throw new CopilotException(ErrorCode.INVALID_REQUEST, "Only a verdict is accepted");
        }
        return service.submit(analysisId, body.get("verdict").textValue(), authorization.substring(7));
    }
}
