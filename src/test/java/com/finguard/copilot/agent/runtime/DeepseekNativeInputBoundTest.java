package com.finguard.copilot.agent.runtime;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.math.BigDecimal;
import java.net.URI;
import java.time.*;
import java.util.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.finguard.copilot.agent.budget.*;
import com.finguard.copilot.agent.model.*;
import com.finguard.copilot.agent.tool.*;
import com.finguard.copilot.analysis.application.ModelAnalysisOrchestrator;
import com.finguard.copilot.config.*;
import com.finguard.copilot.context.*;
import com.finguard.copilot.coreclient.port.ReviewTaskContext;
import com.finguard.copilot.governance.GovernanceProperties;
import org.junit.jupiter.api.Test;

class DeepseekNativeInputBoundTest {
    final ObjectMapper json=new ObjectMapper();final UUID id=UUID.randomUUID();final ToolRegistry registry=new ToolRegistry();
    CopilotProperties.Model model(String name,String url){return new CopilotProperties.Model("deepseek",name,URI.create(url),new SecretValue("unit-test-only"),"/chat/completions",32000,2000,Duration.ofSeconds(5),"deepseek-flash-native-20261104-peak-miss-cny-v1","CNY",new BigDecimal("2"),new BigDecimal("8"));}
    CopilotProperties.Model real(){return model("deepseek-flash","https://api.deepseek.com");}
    ArrayNode initial(){var m=json.createArrayNode();m.addObject().put("role","system").put("content","Output JSON; synthetic qualification only.");m.addObject().put("role","user").put("content","Read the task.");return m;}
    ArrayNode withTools(){var m=initial();m.addObject().put("role","assistant").putArray("tool_calls").addObject().put("id","call1").put("type","function").putObject("function").put("name","get_task_state").put("arguments","{}");m.addObject().put("role","tool").put("tool_call_id","call1").put("content","{\"status\":\"PENDING\"}");return m;}
    AgentTurnRequest request(ArrayNode m,int ordinal){return new AgentTurnRequest(id,ordinal,m,ordinal==1,2000);}
    @Test void acceptsOnlyTheReviewedModelEndpointAndGrammar(){
        var bound=new DeepseekNativeInputBound();
        assertThat(bound.upperBound(real(),request(initial(),1),registry)).isGreaterThan(8192);
        for(var bad:List.of(model("deepseek-v4-pro","https://api.deepseek.com"),model("deepseek-flash","http://127.0.0.1:18765"),model("deepseek-flash","https://other.example")))
            assertThatThrownBy(()->bound.upperBound(bad,request(initial(),1),registry)).hasMessageContaining("native");
        var bad=initial();bad.get(1).deepCopy();((com.fasterxml.jackson.databind.node.ObjectNode)bad.get(1)).put("url","https://other.example");
        assertThatThrownBy(()->bound.upperBound(real(),request(bad,1),registry));
    }
    @Test void includesEveryToolResultAndAllowsOnlyMatchingToolIds(){
        var bound=new DeepseekNativeInputBound();var m=withTools();int before=bound.upperBound(real(),request(m,2),registry);
        ((com.fasterxml.jackson.databind.node.ObjectNode)m.get(3)).put("content","x".repeat(1024));
        assertThat(bound.upperBound(real(),request(m,2),registry)-before).isGreaterThan(900);
        ((com.fasterxml.jackson.databind.node.ObjectNode)m.get(3)).put("tool_call_id","foreign");
        assertThatThrownBy(()->bound.upperBound(real(),request(m,2),registry));
    }
    @Test void rejectsMalformedUnicodeAndOversizedRequestsBeforeAnyClaim(){
        var bound=new DeepseekNativeInputBound();var m=initial();
        ((com.fasterxml.jackson.databind.node.ObjectNode)m.get(1)).put("content",Character.toString((char)0xd800));
        assertThatThrownBy(()->bound.upperBound(real(),request(m,1),registry));
        ((com.fasterxml.jackson.databind.node.ObjectNode)m.get(1)).put("content","x".repeat(32000));
        assertThatThrownBy(()->bound.upperBound(real(),request(m,1),registry)).hasMessageContaining("budget");
    }
    @Test void acceptsTheSingleRepairTurnAndRejectsUnexpectedAssistantContent(){
        var bound=new DeepseekNativeInputBound();var repair=initial();repair.addObject().put("role","user").put("content","Return valid JSON.");
        assertThat(bound.upperBound(real(),request(repair,2),registry)).isPositive();
        var m=withTools();((com.fasterxml.jackson.databind.node.ObjectNode)m.get(2)).put("content","unmeasured assistant continuation");
        assertThatThrownBy(()->bound.upperBound(real(),request(m,2),registry));
    }
    @Test void assistantContainersAndNumericCallIdsCannotEnterTheTextOnlyContract(){
        var bound=new DeepseekNativeInputBound();
        for(JsonNode content:List.of(json.createArrayNode().addObject().put("type","image_url"),json.createObjectNode().put("unexpected","content"))){
            var m=withTools();((com.fasterxml.jackson.databind.node.ObjectNode)m.get(2)).set("content",content);
            assertThatThrownBy(()->bound.upperBound(real(),request(m,2),registry));
        }
        var m=withTools();((com.fasterxml.jackson.databind.node.ObjectNode)m.get(2).path("tool_calls").get(0)).put("id",1);
        ((com.fasterxml.jackson.databind.node.ObjectNode)m.get(3)).put("tool_call_id",1);
        assertThatThrownBy(()->bound.upperBound(real(),request(m,2),registry));
    }
    @Test void arbitraryPositivePricesCannotUnderstateTheReviewedCost(){
        var original=real();var cheap=new CopilotProperties.Model(original.provider(),original.modelId(),original.baseUrl(),original.apiKey(),original.chatCompletionsPath(),32000,2000,original.timeout(),original.priceTableVersion(),"CNY",new BigDecimal("0.01"),new BigDecimal("0.01"));
        assertThatThrownBy(()->runner(cheap).prepare(context(),request(initial(),1))).hasMessageContaining("price");
    }
    @Test void nativePolicyFreezesTheEntirePriceAndValidity(){
        var prepared=runner(real()).prepare(context(),request(initial(),1));
        assertThat(prepared.policy()).containsKeys("price","priceValidFrom","priceValidUntil");
    }
    BoundedAgentRunner runner(CopilotProperties.Model model){return new BoundedAgentRunner(mock(AgentModelGateway.class),mock(ToolExecutor.class),mock(AgentCallAdmission.class),mock(AgentCallLedger.class),ignored->context(),mock(ModelAnalysisOrchestrator.class),new AgentRuntimeSettings(model,GovernanceProperties.defaults(),new BigDecimal("0.20"),DeepseekNativeInputBound.VERSION),Clock.fixed(Instant.parse("2026-11-04T01:00:00Z"),ZoneOffset.UTC));}
    @Test void realNativeRequiresExplicitVersionAndFreezesItsEvidenceInPolicy(){
        var gateway=mock(AgentModelGateway.class);var tools=mock(ToolExecutor.class);var admission=mock(AgentCallAdmission.class);var ledger=mock(AgentCallLedger.class);
        var context=context();var validator=mock(ModelAnalysisOrchestrator.class);
        var denied=new BoundedAgentRunner(gateway,tools,admission,ledger,ignored->context,validator,new AgentRuntimeSettings(real(),GovernanceProperties.defaults(),new BigDecimal("0.20")));
        assertThatThrownBy(()->denied.prepare(context,request(initial(),1))).hasMessageContaining("native");
        var allowed=new BoundedAgentRunner(gateway,tools,admission,ledger,ignored->context,validator,new AgentRuntimeSettings(real(),GovernanceProperties.defaults(),new BigDecimal("0.20"),DeepseekNativeInputBound.VERSION),Clock.fixed(Instant.parse("2026-11-04T01:00:00Z"),ZoneOffset.UTC));
        var prepared=allowed.prepare(context,request(initial(),1));
        assertThat(prepared.inputBoundVersion()).isEqualTo(DeepseekNativeInputBound.VERSION);
        assertThat(prepared.inputBoundEvidenceSha256()).matches("[a-f0-9]{64}");
        assertThat(prepared.policy()).containsEntry("inputBoundVersion",DeepseekNativeInputBound.VERSION).containsEntry("inputBoundEvidenceSha256",prepared.inputBoundEvidenceSha256());
        verifyNoInteractions(gateway,tools,admission,ledger);
    }
    AgentRunContext context(){var now=Instant.now();var facts=new ReviewTaskContext(new ReviewTaskContext.ReviewTask(42,0,"PENDING","LARGE_AMOUNT","REVIEW",now),new ReviewTaskContext.Transaction(2,3,new BigDecimal("100.00"),"CNY",now,"CSV_IMPORT"),new ReviewTaskContext.Reconciliation("SUSPICIOUS","NONE","REVIEW"),List.of(),new ReviewTaskContext.AccountSummary("ACTIVE","Synthetic"),now);return new AgentRunContext(id,"native-unit-reviewer",new SnapshotCodec().capture(facts),"frozen");}
}
