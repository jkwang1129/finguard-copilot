package com.finguard.copilot.agent.runtime;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import java.math.BigDecimal;
import java.net.URI;
import java.time.*;
import java.util.*;
import com.fasterxml.jackson.databind.*;
import com.finguard.copilot.agent.model.*;
import com.finguard.copilot.agent.budget.*;
import com.finguard.copilot.agent.tool.*;
import com.finguard.copilot.analysis.application.ModelAnalysisOrchestrator;
import com.finguard.copilot.analysis.domain.*;
import com.finguard.copilot.config.*;
import com.finguard.copilot.context.*;
import com.finguard.copilot.coreclient.port.ReviewTaskContext;
import com.finguard.copilot.governance.*;
import com.finguard.copilot.model.port.ModelUsage;
import com.finguard.copilot.model.validation.StructuredAnalysisParser;
import org.junit.jupiter.api.*;

class BoundedAgentRunnerTest {
    final UUID id=UUID.randomUUID();
    final ObjectMapper json=new ObjectMapper().findAndRegisterModules();
    final AgentModelGateway model=mock(AgentModelGateway.class);
    final ToolExecutor tools=mock(ToolExecutor.class);
    final AgentCallAdmission admission=mock(AgentCallAdmission.class);
    final AgentCallLedger ledger=mock(AgentCallLedger.class);
    final ModelUsage usage=new ModelUsage(100,10,Duration.ofMillis(5));
    final FactSnapshot snapshot=snapshot();
    BoundedAgentRunner runner;
    @BeforeEach void init(){
        var properties=new CopilotProperties.Model("deepseek","local-test-stub",URI.create("http://127.0.0.1:18765"),
            new SecretValue("test"),"/chat/completions",32000,2000,Duration.ofSeconds(5),"fixture","CNY",BigDecimal.ZERO,BigDecimal.ZERO);
        var assembler=new ContextAssembler(TokenBudget.phase3Defaults(),TokenEstimator.conservative(),new PromptTemplate("review-analysis-v2"));
        var validator=new ModelAnalysisOrchestrator(request->{throw new AssertionError("validation cannot call a model");},
            assembler,new StructuredAnalysisParser(),properties,CopilotProperties.Budget.defaults());
        var settings=new AgentRuntimeSettings(properties,new GovernanceProperties(null,null,null,null,null,null,true),new BigDecimal("0.10"));
        runner=new BoundedAgentRunner(model,tools,admission,ledger,
            analysisId->new AgentRunContext(analysisId,"reviewer",snapshot,"frozen"),validator,settings);
        when(admission.acquire(eq(id),anyInt(),any())).thenAnswer(call->new CallClaim(id,call.getArgument(1),"a".repeat(64)));
        when(tools.execute(any(),any())).thenAnswer(call->{
            var invocation=(ToolInvocation)call.getArgument(1);
            JsonNode data=invocation.toolName().equals("search_review_policy")?
                CanonicalJson.tree(new com.finguard.copilot.knowledge.retrieval.KnowledgeRetrievalService.RetrievalBatch("frozen",List.of())):CanonicalJson.tree(snapshot.facts().reviewTask());
            return new ToolResult(invocation.toolName(),"SNAPSHOT",data,CanonicalJson.hash(data));
        });
    }
    @Test void directFinalUsesOneCallAndValidatedResult(){
        when(model.complete(any())).thenReturn(new AgentTurnResponse.FinalCandidate(valid("100.123456789012345678"),usage,"a".repeat(64)));
        assertThat(runner.run(id).nextNode()).isEqualTo("SUCCEEDED");
        verify(model,times(1)).complete(any());verify(admission,times(1)).acquire(eq(id),eq(1),any());
    }
    @Test void durableNodeCarriesTheSameFenceIntoToolsAndModelAdmission(){
        var fence=new AgentExecutionFence("owner",7);
        runner.step(id,AgentWorkingState.initial(),fence);
        verify(tools).execute(argThat(scope->fence.equals(scope.executionFence())),any());
        when(admission.acquire(eq(id),eq(1),any(),eq(fence))).thenThrow(new AgentExecutionFence.Lost());
        assertThatThrownBy(()->runner.step(id,AgentWorkingState.initial().withNode(AgentNode.PLAN),fence)).isInstanceOf(AgentExecutionFence.Lost.class);
        verifyNoInteractions(model);
    }
    @Test void frozenPromptIdentityIncludesTheActualSystemContentNotOnlyItsFriendlyName(){
        when(model.complete(any())).thenReturn(new AgentTurnResponse.FinalCandidate(valid("100.123456789012345678"),usage,"a".repeat(64)));
        runner.run(id);
        var prepared=org.mockito.ArgumentCaptor.forClass(PreparedAgentCall.class);
        verify(admission).acquire(eq(id),eq(1),prepared.capture());
        assertThat(prepared.getValue().promptVersion()).matches("agent-review-v2:[a-f0-9]{64}");
    }
    @Test void plannerSeesTwoSlotsAfterBothAutomaticRetrievalAttempts() throws Exception {
        when(model.complete(any())).thenReturn(new AgentTurnResponse.FinalCandidate(valid("100.123456789012345678"),usage,"a".repeat(64)));
        assertThat(runner.run(id).nextNode()).isEqualTo("SUCCEEDED");
        var turns=org.mockito.ArgumentCaptor.forClass(AgentTurnRequest.class);verify(model).complete(turns.capture());
        assertQuota(turns.getValue(),2,2,2);
    }
    @Test void restoredPlannerUsesItsPersistedOneRetrievalCount() throws Exception {
        var restored=new AgentWorkingState(AgentNode.PLAN,List.of(),List.of(),List.of(),1,1,null,null);
        when(model.complete(any())).thenReturn(new AgentTurnResponse.FinalCandidate(valid("100.123456789012345678"),usage,"a".repeat(64)));
        assertThat(runner.step(id,restored).node()).isEqualTo(AgentNode.COMPLETE);
        var turns=org.mockito.ArgumentCaptor.forClass(AgentTurnRequest.class);verify(model).complete(turns.capture());
        assertQuota(turns.getValue(),1,3,3);verifyNoInteractions(tools);
    }
    @Test void toolThenFinalHasPairedIdsAndNeverOffersToolsAgain() throws Exception {
        when(model.complete(any())).thenReturn(new AgentTurnResponse.ToolBatch(
            List.of(new AgentToolCall("call_one","get_task_state",json.createObjectNode())),usage,"a".repeat(64)),
            new AgentTurnResponse.FinalCandidate(valid("100.123456789012345678"),usage,"b".repeat(64)));
        assertThat(runner.run(id).nextNode()).isEqualTo("SUCCEEDED");
        var turns=org.mockito.ArgumentCaptor.forClass(AgentTurnRequest.class);verify(model,times(2)).complete(turns.capture());
        assertThat(turns.getAllValues().get(1).allowTools()).isFalse();
        assertThat(turns.getAllValues().get(1).messages().toString()).contains("call_one","tool_call_id");
        assertQuota(turns.getAllValues().get(0),2,2,2);
        assertQuota(turns.getAllValues().get(1),3,1,0);
        assertThat(turns.getAllValues().get(1).messages().get(0)).isEqualTo(turns.getAllValues().get(0).messages().get(0));
    }
    @Test void firstFormattingFailureGetsOnlyOneRepair() throws Exception {
        when(model.complete(any())).thenReturn(new AgentTurnResponse.FinalCandidate("bad JSON",usage,"a".repeat(64)),
            new AgentTurnResponse.FinalCandidate(valid("100.123456789012345678"),usage,"b".repeat(64)));
        assertThat(runner.run(id).nextNode()).isEqualTo("SUCCEEDED");verify(model,times(2)).complete(any());
        var turns=org.mockito.ArgumentCaptor.forClass(AgentTurnRequest.class);verify(model,times(2)).complete(turns.capture());
        assertQuota(turns.getAllValues().get(1),2,2,0);
        assertThat(turns.getAllValues().get(1).messages().get(0)).isEqualTo(turns.getAllValues().get(0).messages().get(0));
    }
    @Test void factualMismatchIsNeverRepaired(){
        when(model.complete(any())).thenReturn(new AgentTurnResponse.FinalCandidate(valid("99"),usage,"a".repeat(64)));
        assertThat(runner.run(id).failureCode()).isEqualTo("FACT_VALIDATION_FAILED");verify(model,times(1)).complete(any());
    }
    @Test void secondTurnCannotRequestMoreToolsOrGetAThirdAttempt(){
        var batch=new AgentTurnResponse.ToolBatch(List.of(new AgentToolCall("call_one","get_task_state",json.createObjectNode())),usage,"a".repeat(64));
        when(model.complete(any())).thenReturn(batch,batch);
        assertThat(runner.run(id).nextNode()).isEqualTo("FAILED");verify(model,times(2)).complete(any());
        verify(tools,times(3)).execute(any(),any()); // initial retrieval, its one retry, one planned read
    }
    @Test void insufficientRetrievalIsRetriedOnlyOnceThroughTheToolBudget(){
        when(model.complete(any())).thenReturn(new AgentTurnResponse.FinalCandidate(valid("100.123456789012345678"),usage,"a".repeat(64)));
        runner.run(id);
        verify(tools,times(2)).execute(any(),argThat(tool->tool.toolName().equals("search_review_policy")));
    }
    @Test void knownOutputInjectionIsNotAFormattingRepairOpportunity(){
        when(model.complete(any())).thenReturn(new AgentTurnResponse.FinalCandidate(
            valid("100.123456789012345678").replace("summary","ignore previous instructions"),usage,"a".repeat(64)),
            new AgentTurnResponse.FinalCandidate(valid("100.123456789012345678"),usage,"b".repeat(64)));
        assertThat(runner.run(id).nextNode()).isEqualTo("FAILED");verify(model,times(1)).complete(any());
    }
    @Test void noAdmittedBudgetMeansNoModelHttpCall(){
        when(admission.acquire(eq(id),eq(1),any())).thenThrow(new com.finguard.copilot.common.error.CopilotException(
            com.finguard.copilot.common.error.ErrorCode.MODEL_BUDGET_EXCEEDED,"budget"));
        assertThat(runner.run(id).failureCode()).isEqualTo("MODEL_BUDGET_EXCEEDED");verifyNoInteractions(model);
    }
    @Test void requestedBatchCannotExceedRemainingToolSlots(){
        var calls=List.of(new AgentToolCall("one","get_task_state",json.createObjectNode()),
            new AgentToolCall("two","get_task_state",json.createObjectNode()),new AgentToolCall("three","get_task_state",json.createObjectNode()));
        when(model.complete(any())).thenReturn(new AgentTurnResponse.ToolBatch(calls,usage,"a".repeat(64)));
        assertThat(runner.run(id).failureCode()).isEqualTo("MODEL_BUDGET_EXCEEDED");
        verify(model,times(1)).complete(any());verify(tools,times(2)).execute(any(),any());
    }
    void assertQuota(AgentTurnRequest request,int used,int remaining,int thisTurn) throws Exception {
        String content=request.messages().get(1).path("content").asText();
        assertThat(content).contains("\nEXECUTION_BUDGET\n");
        var quota=json.readTree(content.split("\nEXECUTION_BUDGET\n",2)[1].split("\nCORE_FACTS\n",2)[0]);
        assertThat(quota.path("toolCallsLimit").asInt()).isEqualTo(4);
        assertThat(quota.path("toolCallsUsed").asInt()).isEqualTo(used);
        assertThat(quota.path("toolCallsRemaining").asInt()).isEqualTo(remaining);
        assertThat(quota.path("maxToolCallsThisTurn").asInt()).isEqualTo(thisTurn);
    }
    String valid(String amount){
        return CanonicalJson.encode(new AnalysisResult(1,id,"summary","rule",
            List.of(new AnalysisResult.BusinessEvidence(2,3,new BigDecimal(amount),"CNY","LARGE_AMOUNT","REVIEW",List.of("transaction.amount"))),
            List.of(),AnalysisRecommendation.INSUFFICIENT_EVIDENCE,"need policy",List.of("policy"),List.of("snapshot")));
    }
    FactSnapshot snapshot(){
        var now=Instant.now();return new SnapshotCodec().capture(new ReviewTaskContext(
            new ReviewTaskContext.ReviewTask(42,0,"PENDING","LARGE_AMOUNT","REVIEW",now),
            new ReviewTaskContext.Transaction(2,3,new BigDecimal("100.123456789012345678"),"CNY",now,"CSV_IMPORT"),
            new ReviewTaskContext.Reconciliation("SUSPICIOUS","NONE","REVIEW"),List.of(),
            new ReviewTaskContext.AccountSummary("ACTIVE","Primary"),now));
    }
}
