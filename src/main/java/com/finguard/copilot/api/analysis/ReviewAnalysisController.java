package com.finguard.copilot.api.analysis;

import java.util.UUID;

import com.finguard.copilot.analysis.application.CreateAnalysisCommand;
import com.finguard.copilot.analysis.application.CreateAnalysisService;
import com.finguard.copilot.analysis.application.GetAnalysisService;
import com.finguard.copilot.common.error.CopilotException;
import com.finguard.copilot.common.error.ErrorCode;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/copilot/review-analyses")
public class ReviewAnalysisController {

    private final CreateAnalysisService createService;
    private final GetAnalysisService getService;

    public ReviewAnalysisController(CreateAnalysisService createService, GetAnalysisService getService) {
        this.createService = createService;
        this.getService = getService;
    }

    @PostMapping
    public ResponseEntity<AnalysisAcceptedResponse> create(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody CreateAnalysisRequest request
    ) {
        String bearerToken = bearerToken(authorization);
        AnalysisAcceptedResponse response = createService.create(
                new CreateAnalysisCommand(request.reviewTaskId(), requireIdempotencyKey(idempotencyKey)),
                bearerToken);
        java.net.URI location = java.net.URI.create(
                "/api/copilot/review-analyses/" + response.analysisId());
        return ResponseEntity.accepted().location(location).body(response);
    }

    @GetMapping("/{analysisId}")
    public AnalysisResponse get(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @PathVariable UUID analysisId
    ) {
        return getService.get(analysisId, bearerToken(authorization));
    }

    private static String bearerToken(String authorization) {
        if (authorization == null || authorization.length() <= 7
                || !authorization.regionMatches(true, 0, "Bearer ", 0, 7)) {
            throw new CopilotException(ErrorCode.UNAUTHENTICATED,
                    "Authentication is required");
        }
        return authorization.substring(7);
    }

    private static String requireIdempotencyKey(String idempotencyKey) {
        if (idempotencyKey == null) {
            throw new CopilotException(ErrorCode.INVALID_REQUEST,
                    "Idempotency-Key must contain 1 to 100 visible ASCII characters");
        }
        return idempotencyKey;
    }
}
