package com.finguard.copilot.analysis.application;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.finguard.copilot.analysis.domain.AnalysisStatus;
import com.finguard.copilot.analysis.persistence.AnalysisRunRepository;
import com.finguard.copilot.analysis.persistence.AnalysisRunRepository.AnalysisView;
import com.finguard.copilot.api.analysis.AnalysisResponse;
import com.finguard.copilot.common.error.CopilotException;
import com.finguard.copilot.common.error.ErrorCode;
import com.finguard.copilot.coreclient.port.CoreClient;
import com.finguard.copilot.coreclient.port.CurrentUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GetAnalysisServiceTest {

    private static final String TOKEN = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxIn0.signature";
    private static final UUID ANALYSIS_ID = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");

    private final CoreClient coreClient = mock(CoreClient.class);
    private final AnalysisRunRepository runRepository = mock(AnalysisRunRepository.class);
    private GetAnalysisService service;

    @BeforeEach
    void setUp() {
        service = new GetAnalysisService(coreClient, runRepository, new ObjectMapper());
    }

    @Test
    void callsCoreCurrentUserAndUsesReviewerScopedLookup() {
        when(coreClient.currentUser(TOKEN)).thenReturn(new CurrentUser("reviewer", Set.of("REVIEWER")));
        when(runRepository.findByIdAndReviewer(ANALYSIS_ID, "reviewer"))
                .thenReturn(Optional.of(new AnalysisView(
                        ANALYSIS_ID, AnalysisStatus.SUCCEEDED, null,
                        "{\"recommendation\":\"CONFIRMED\"}")));

        AnalysisResponse response = service.get(ANALYSIS_ID, TOKEN);

        assertThat(response.status()).isEqualTo(AnalysisStatus.SUCCEEDED);
        assertThat(response.result().get("recommendation").asText()).isEqualTo("CONFIRMED");
        verify(coreClient).currentUser(TOKEN);
        verify(runRepository).findByIdAndReviewer(ANALYSIS_ID, "reviewer");
    }

    @Test
    void rejectsAdminBeforeScopedLookup() {
        when(coreClient.currentUser(TOKEN)).thenReturn(new CurrentUser("admin", Set.of("ADMIN")));

        assertThatThrownBy(() -> service.get(ANALYSIS_ID, TOKEN))
                .isInstanceOf(CopilotException.class)
                .extracting(exception -> ((CopilotException) exception).code())
                .isEqualTo(ErrorCode.FORBIDDEN);

        verify(runRepository, never()).findByIdAndReviewer(ANALYSIS_ID, "admin");
    }

    @Test
    void hidesCrossReviewerAnalysisAs404() {
        when(coreClient.currentUser(TOKEN)).thenReturn(new CurrentUser("reviewer", Set.of("REVIEWER")));
        when(runRepository.findByIdAndReviewer(ANALYSIS_ID, "reviewer"))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.get(ANALYSIS_ID, TOKEN))
                .isInstanceOf(CopilotException.class)
                .hasMessage("Analysis run was not found")
                .extracting(exception -> ((CopilotException) exception).code())
                .isEqualTo(ErrorCode.RESOURCE_NOT_FOUND);
    }

    @Test
    void exposesOnlyBoundedFailureCodeForFailedRun() {
        when(coreClient.currentUser(TOKEN)).thenReturn(new CurrentUser("reviewer", Set.of("REVIEWER")));
        when(runRepository.findByIdAndReviewer(ANALYSIS_ID, "reviewer"))
                .thenReturn(Optional.of(new AnalysisView(
                        ANALYSIS_ID, AnalysisStatus.FAILED, "ANALYSIS_RETRY_EXHAUSTED", null)));

        AnalysisResponse response = service.get(ANALYSIS_ID, TOKEN);

        assertThat(response.failureCode()).isEqualTo("ANALYSIS_RETRY_EXHAUSTED");
        assertThat(response.result()).isNull();
    }

    @Test
    void mapsMalformedSucceededResultToBoundedInternalError() {
        when(coreClient.currentUser(TOKEN)).thenReturn(new CurrentUser("reviewer", Set.of("REVIEWER")));
        when(runRepository.findByIdAndReviewer(ANALYSIS_ID, "reviewer"))
                .thenReturn(Optional.of(new AnalysisView(
                        ANALYSIS_ID, AnalysisStatus.SUCCEEDED, null, "{malformed")));

        assertThatThrownBy(() -> service.get(ANALYSIS_ID, TOKEN))
                .isInstanceOf(CopilotException.class)
                .hasMessage("Analysis result could not be read")
                .extracting(exception -> ((CopilotException) exception).code())
                .isEqualTo(ErrorCode.INTERNAL_ERROR);
    }

    @Test
    void omitsResultAndFailureForIntermediateRun() {
        when(coreClient.currentUser(TOKEN)).thenReturn(new CurrentUser("reviewer", Set.of("REVIEWER")));
        when(runRepository.findByIdAndReviewer(ANALYSIS_ID, "reviewer"))
                .thenReturn(Optional.of(new AnalysisView(
                        ANALYSIS_ID, AnalysisStatus.GENERATING, null, null)));

        AnalysisResponse response = service.get(ANALYSIS_ID, TOKEN);

        assertThat(response.result()).isNull();
        assertThat(response.failureCode()).isNull();
    }
}
