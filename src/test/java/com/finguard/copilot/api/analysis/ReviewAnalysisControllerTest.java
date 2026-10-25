package com.finguard.copilot.api.analysis;

import java.util.UUID;

import com.finguard.copilot.api.error.GlobalExceptionHandler;
import com.finguard.copilot.analysis.application.CreateAnalysisService;
import com.finguard.copilot.analysis.application.GetAnalysisService;
import com.finguard.copilot.analysis.domain.AnalysisStatus;
import com.finguard.copilot.common.error.CopilotException;
import com.finguard.copilot.common.error.ErrorCode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ReviewAnalysisControllerTest {

    private static final String TOKEN = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxIn0.signature";
    private static final UUID ANALYSIS_ID = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");

    private final CreateAnalysisService createService = mock(CreateAnalysisService.class);
    private final GetAnalysisService getService = mock(GetAnalysisService.class);
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new ReviewAnalysisController(createService, getService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void acceptsNewAndReplayedCreatesWithLocationAndNoTokenInResponse() throws Exception {
        when(createService.create(any(), eq(TOKEN)))
                .thenReturn(new AnalysisAcceptedResponse(ANALYSIS_ID, AnalysisStatus.CONTEXT_READY, true));

        mockMvc.perform(post("/api/copilot/review-analyses")
                        .header("Authorization", "Bearer " + TOKEN)
                        .header("Idempotency-Key", "request-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reviewTaskId\":123}"))
                .andExpect(status().isAccepted())
                .andExpect(header().string("Location",
                        "/api/copilot/review-analyses/" + ANALYSIS_ID))
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.analysisId").value(ANALYSIS_ID.toString()))
                .andExpect(jsonPath("$.status").value("CONTEXT_READY"))
                .andExpect(jsonPath("$.replayed").value(true))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString(TOKEN))));
    }

    @Test
    void returnsReviewerScopedQueryAndOnlyTerminalFields() throws Exception {
        when(getService.get(ANALYSIS_ID, TOKEN)).thenReturn(new AnalysisResponse(
                ANALYSIS_ID, AnalysisStatus.SUCCEEDED,
                new ObjectMapper().readTree("{\"recommendation\":\"CONFIRMED\"}"), null));

        mockMvc.perform(get("/api/copilot/review-analyses/" + ANALYSIS_ID)
                        .header("Authorization", "Bearer " + TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.analysisId").value(ANALYSIS_ID.toString()))
                .andExpect(jsonPath("$.status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.result.recommendation").value("CONFIRMED"))
                .andExpect(jsonPath("$.failureCode").doesNotExist())
                .andExpect(jsonPath("$.snapshot").doesNotExist());
    }

    @Test
    void mapsMissingBearerTokenTo401ProblemDetail() throws Exception {
        mockMvc.perform(get("/api/copilot/review-analyses/" + ANALYSIS_ID))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentType("application/problem+json"))
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    @Test
    void mapsMissingIdempotencyKeyTo400ProblemDetailWithoutCallingApplicationService()
            throws Exception {
        mockMvc.perform(post("/api/copilot/review-analyses")
                        .header("Authorization", "Bearer " + TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reviewTaskId\":123}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType("application/problem+json"))
                .andExpect(jsonPath("$.type")
                        .value("urn:finguard:error:invalid-request"))
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.detail")
                        .value("Idempotency-Key must contain 1 to 100 visible ASCII characters"));

        org.mockito.Mockito.verifyNoInteractions(createService);
    }

    @Test
    void mapsCoreUnavailableTo503ProblemDetail() throws Exception {
        when(createService.create(any(), eq(TOKEN))).thenThrow(
                new CopilotException(ErrorCode.SERVICE_UNAVAILABLE, "Core service is unavailable"));

        mockMvc.perform(post("/api/copilot/review-analyses")
                        .header("Authorization", "Bearer " + TOKEN)
                        .header("Idempotency-Key", "request-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reviewTaskId\":123}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("SERVICE_UNAVAILABLE"));
    }
}
