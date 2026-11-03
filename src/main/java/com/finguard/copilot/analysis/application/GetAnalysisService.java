package com.finguard.copilot.analysis.application;

import java.util.UUID;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finguard.copilot.analysis.domain.AnalysisStatus;
import com.finguard.copilot.analysis.persistence.AnalysisRunRepository;
import com.finguard.copilot.analysis.persistence.AnalysisRunRepository.AnalysisView;
import com.finguard.copilot.api.analysis.AnalysisResponse;
import com.finguard.copilot.common.error.CopilotException;
import com.finguard.copilot.common.error.ErrorCode;
import com.finguard.copilot.coreclient.port.CoreClient;
import com.finguard.copilot.coreclient.port.CoreClientException;
import com.finguard.copilot.coreclient.port.CurrentUser;
import org.springframework.stereotype.Service;

@Service
public class GetAnalysisService {

    private final CoreClient coreClient;
    private final AnalysisRunRepository runRepository;
    private final ObjectMapper objectMapper;

    public GetAnalysisService(
            CoreClient coreClient,
            AnalysisRunRepository runRepository,
            ObjectMapper objectMapper
    ) {
        this.coreClient = coreClient;
        this.runRepository = runRepository;
        this.objectMapper = objectMapper.copy()
                .enable(com.fasterxml.jackson.databind.DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
    }

    public AnalysisResponse get(UUID analysisId, String bearerToken) {
        AnalysisView view=authorize(analysisId,bearerToken).view();
        return response(view);
    }

    public record AuthorizedAnalysis(String reviewer,AnalysisView view) {}
    public AuthorizedAnalysis authorize(UUID analysisId,String bearerToken) {
        CurrentUser currentUser = currentUser(bearerToken);
        if (!currentUser.roles().contains("REVIEWER")) {
            throw new CopilotException(ErrorCode.FORBIDDEN, "Reviewer access is required");
        }
        AnalysisView view = runRepository.findByIdAndReviewer(analysisId, currentUser.username())
                .orElseThrow(() -> new CopilotException(
                        ErrorCode.RESOURCE_NOT_FOUND, "Analysis run was not found"));
        return new AuthorizedAnalysis(currentUser.username(),view);
    }
    private AnalysisResponse response(AnalysisView view) {
        JsonNode result = null;
        if (view.status() == AnalysisStatus.SUCCEEDED) {
            try {
                result = objectMapper.readTree(view.resultJson());
            } catch (JsonProcessingException | IllegalArgumentException exception) {
                throw new CopilotException(ErrorCode.INTERNAL_ERROR,
                        "Analysis result could not be read");
            }
        }
        String failureCode = view.status() == AnalysisStatus.FAILED ? view.failureCode() : null;
        return new AnalysisResponse(view.analysisId(), view.status(), result, failureCode);
    }

    private CurrentUser currentUser(String bearerToken) {
        try {
            return coreClient.currentUser(bearerToken);
        } catch (CoreClientException exception) {
            throw switch (exception.kind()) {
                case UNAUTHENTICATED -> new CopilotException(
                        ErrorCode.UNAUTHENTICATED, "Core authentication is required");
                case FORBIDDEN -> new CopilotException(
                        ErrorCode.FORBIDDEN, "Core access is forbidden");
                case NOT_FOUND -> new CopilotException(
                        ErrorCode.RESOURCE_NOT_FOUND, "The requested Core resource was not found");
                case UNAVAILABLE, INVALID_RESPONSE -> new CopilotException(
                        ErrorCode.SERVICE_UNAVAILABLE, "Core service is unavailable");
            };
        }
    }
}
