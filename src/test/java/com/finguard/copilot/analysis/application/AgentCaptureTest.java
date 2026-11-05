package com.finguard.copilot.analysis.application;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import com.finguard.copilot.analysis.persistence.AnalysisCreationStore;
import com.finguard.copilot.analysis.domain.AnalysisStatus;
import com.finguard.copilot.context.*;
import com.finguard.copilot.coreclient.port.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class AgentCaptureTest {
    final CoreClient core=mock(CoreClient.class);
    final AnalysisCreationStore store=mock(AnalysisCreationStore.class);
    final CreateAnalysisService service=new CreateAnalysisService(core,new SnapshotCodec(),store);
    final ReviewTaskContext facts=facts();
    @BeforeEach void init() {
        service.configureAgent(true);
        when(core.currentUser("token")).thenReturn(new CurrentUser("reviewer",Set.of("REVIEWER")));
        when(store.findReservation("reviewer","key")).thenReturn(Optional.empty());
        when(core.reviewTaskContext(42,"token")).thenReturn(facts);
        when(store.create(any(),any(),any(),any())).thenReturn(
            new AnalysisCreationStore.CreatedAnalysis(UUID.randomUUID(),AnalysisStatus.CONTEXT_READY));
    }
    @Test void verifiesSecondReadAndFreezesAgentPolicyWithoutSensitiveFields() {
        when(core.reviewTaskState(42,"token")).thenReturn(new ReviewTaskState(42,0,"PENDING","LARGE_AMOUNT","REVIEW"));
        service.create(new CreateAnalysisCommand(42,"key"),"token");
        var capture=org.mockito.ArgumentCaptor.forClass(AnalysisCreationStore.NewAnalysis.class);
        verify(store).create(capture.capture(),any(),any(),any());
        assertThat(capture.getValue().workflowVersion()).isEqualTo("agent-v1");
        var order=inOrder(core,store);
        order.verify(core).currentUser("token");
        order.verify(store).findReservation("reviewer","key");
        order.verify(core).reviewTaskContext(42,"token");
        order.verify(core).reviewTaskState(42,"token");
        order.verify(store).create(any(),any(),any(),any());
    }
    @Test void rejectsEveryTaskProjectionMismatchBeforePersistence() {
        for(var changed:List.of(
            new ReviewTaskState(43,0,"PENDING","LARGE_AMOUNT","REVIEW"),
            new ReviewTaskState(42,1,"PENDING","LARGE_AMOUNT","REVIEW"),
            new ReviewTaskState(42,0,"CONFIRMED","LARGE_AMOUNT","REVIEW"),
            new ReviewTaskState(42,0,"PENDING","OTHER","REVIEW"),
            new ReviewTaskState(42,0,"PENDING","LARGE_AMOUNT","OTHER"))) {
            when(core.reviewTaskState(42,"token")).thenReturn(changed);
            assertThatThrownBy(()->service.create(new CreateAnalysisCommand(42,"key"),"token"))
                .hasMessageContaining("changed");
        }
        verify(store,never()).create(any(),any(),any(),any());
    }
    @ParameterizedTest @EnumSource(CoreClientException.Kind.class)
    void secondReadFailuresNeverCreateAnAcceptedRun(CoreClientException.Kind kind) {
        when(core.reviewTaskState(42,"token")).thenThrow(new CoreClientException(kind,"private",null));
        assertThatThrownBy(()->service.create(new CreateAnalysisCommand(42,"key"),"token"))
            .isInstanceOf(com.finguard.copilot.common.error.CopilotException.class)
            .hasMessageNotContaining("private");
        verify(store,never()).create(any(),any(),any(),any());
    }
    @Test void legacyLaneDoesNotReadTaskState() {
        service.configureAgent(false);
        service.create(new CreateAnalysisCommand(42,"key"),"token");
        verify(core,never()).reviewTaskState(anyLong(),anyString());
    }
    @Test void runtimeChoiceIsFrozenAtCreationInsteadOfBeingReinterpretedAfterRestart(){
        service.configureAgentRuntime(true);
        when(core.reviewTaskState(42,"token")).thenReturn(new ReviewTaskState(42,0,"PENDING","LARGE_AMOUNT","REVIEW"));
        service.create(new CreateAnalysisCommand(42,"key"),"token");
        var capture=org.mockito.ArgumentCaptor.forClass(AnalysisCreationStore.NewAnalysis.class);
        verify(store).create(capture.capture(),any(),any(),any());
        org.assertj.core.api.Assertions.assertThat(capture.getValue().runtimeEnabled()).isTrue();
    }
    static ReviewTaskContext facts() {
        var now=Instant.now();
        return new ReviewTaskContext(new ReviewTaskContext.ReviewTask(42,0,"PENDING","LARGE_AMOUNT","REVIEW",now),
            new ReviewTaskContext.Transaction(2,3,new BigDecimal("100.123456789012345678"),"CNY",now,"CSV_IMPORT"),
            new ReviewTaskContext.Reconciliation("SUSPICIOUS","NONE","REVIEW"),List.of(),
            new ReviewTaskContext.AccountSummary("ACTIVE","Primary"),now);
    }
}
