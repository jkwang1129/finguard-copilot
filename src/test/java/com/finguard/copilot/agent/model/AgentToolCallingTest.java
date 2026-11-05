package com.finguard.copilot.agent.model;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;
import java.net.URI;
import java.time.Duration;
import java.util.UUID;
import com.fasterxml.jackson.databind.*;
import com.finguard.copilot.agent.tool.ToolRegistry;
import com.finguard.copilot.config.*;
import org.junit.jupiter.api.*;
import org.springframework.http.*;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class AgentToolCallingTest {
    final ObjectMapper json=new ObjectMapper();
    final RestClient.Builder builder=RestClient.builder();
    final MockRestServiceServer server=MockRestServiceServer.bindTo(builder).build();
    final CopilotProperties.Model settings=new CopilotProperties.Model("deepseek","deepseek-flash",
        URI.create("https://model.invalid"),new SecretValue("test-only"),"/chat/completions",32000,2000,Duration.ofSeconds(5),"test","CNY",
        java.math.BigDecimal.ONE,java.math.BigDecimal.ONE);
    final AgentModelGateway gateway=new CompatibleAgentModelGateway(settings,builder,new ToolRegistry());
    AgentTurnRequest request(int ordinal){
        var messages=json.createArrayNode();
        messages.addObject().put("role","system").put("content","Return JSON; only approved read tools.");
        messages.addObject().put("role","user").put("content","captured facts");
        return new AgentTurnRequest(UUID.randomUUID(),ordinal,messages,ordinal==1,2000);
    }
    String reply(String message,String reason){
        return "{\"id\":\"provider-private-id\",\"choices\":[{\"finish_reason\":\""+reason+"\",\"message\":"+message+"}],\"usage\":{\"prompt_tokens\":100,\"completion_tokens\":10}}";
    }
    String tool(String name,String args,String id){
        return "{\"id\":\""+id+"\",\"type\":\"function\",\"function\":{\"name\":\""+name+"\",\"arguments\":"+quote(args)+"}}";
    }
    String quote(String text){try{return json.writeValueAsString(text);}catch(Exception e){throw new RuntimeException(e);}}
    @AfterEach void verify(){server.verify();}
    @Test void parsesNativeToolCallsAndUsesPublishedSchema(){
        server.expect(requestTo("https://model.invalid/chat/completions")).andExpect(method(HttpMethod.POST))
            .andExpect(jsonPath("$.thinking.type").value("disabled")).andExpect(jsonPath("$.tool_choice").value("auto"))
            .andExpect(jsonPath("$.tools[0].function.parameters.additionalProperties").value(false))
            .andRespond(withSuccess(reply("{\"role\":\"assistant\",\"content\":null,\"tool_calls\":["+tool("get_task_state","{}","call_1")+"]}","tool_calls"),MediaType.APPLICATION_JSON));
        var response=(AgentTurnResponse.ToolBatch)gateway.complete(request(1));
        assertThat(response.calls()).hasSize(1);assertThat(response.calls().get(0).callId()).isEqualTo("call_1");
        assertThat(response.usage().inputTokens()).isEqualTo(100);
        assertThat(response.providerRequestHash()).doesNotContain("provider-private-id");
    }
    @Test void discardsMixedBodyAndNeverPublishesItAsFinal(){
        server.expect(anything()).andRespond(withSuccess(reply("{\"role\":\"assistant\",\"content\":\"UNVALIDATED-PRIVATE\",\"tool_calls\":["+tool("get_task_state","{}","call_1")+"]}","tool_calls"),MediaType.APPLICATION_JSON));
        var response=gateway.complete(request(1));
        assertThat(response).isInstanceOf(AgentTurnResponse.ToolBatch.class);
        assertThat(response.toString()).doesNotContain("UNVALIDATED-PRIVATE");
    }
    @Test void secondTurnForcesNoToolsAndRejectsUnexpectedToolCalls(){
        server.expect(anything()).andExpect(jsonPath("$.tool_choice").value("none"))
            .andRespond(withSuccess(reply("{\"role\":\"assistant\",\"content\":null,\"tool_calls\":["+tool("get_task_state","{}","call_2")+"]}","tool_calls"),MediaType.APPLICATION_JSON));
        assertThatThrownBy(()->gateway.complete(request(2))).isInstanceOf(AgentProtocolException.class);
    }
    @Test void rejectsLegacyDuplicateUnknownAndMalformedArguments(){
        for(String message:new String[]{
            "{\"role\":\"assistant\",\"content\":\"{}\",\"function_call\":{\"name\":\"get_task_state\"}}",
            "{\"role\":\"assistant\",\"content\":null,\"tool_calls\":["+tool("get_task_state","{}","same")+","+tool("get_task_state","{}","same")+"]}",
            "{\"role\":\"assistant\",\"content\":null,\"tool_calls\":["+tool("http_get","{}","x")+"]}",
            "{\"role\":\"assistant\",\"content\":null,\"tool_calls\":["+tool("get_task_state","", "x")+"]}",
            "{\"role\":\"assistant\",\"content\":null,\"tool_calls\":["+tool("get_task_state","{\"reviewTaskId\":999}","x")+"]}"}) {
            var local=RestClient.builder();var mock=MockRestServiceServer.bindTo(local).build();
            mock.expect(anything()).andRespond(withSuccess(reply(message,"tool_calls"),MediaType.APPLICATION_JSON));
            var adapter=new CompatibleAgentModelGateway(settings,local,new ToolRegistry());
            assertThatThrownBy(()->adapter.complete(request(1))).isInstanceOf(AgentProtocolException.class);
            mock.verify();
        }
    }
    @Test void boundedBodyAndInvalidUsageAreRejectedWithoutLeakingContent(){
        for(String body:new String[]{"x".repeat(65537),"{broken",
            "{\"choices\":[],\"usage\":{\"prompt_tokens\":-1,\"completion_tokens\":10}}"}) {
            var local=RestClient.builder();var mock=MockRestServiceServer.bindTo(local).build();
            mock.expect(anything()).andRespond(withSuccess(body,MediaType.APPLICATION_JSON));
            assertThatThrownBy(()->new CompatibleAgentModelGateway(settings,local,new ToolRegistry()).complete(request(1)))
                .isInstanceOf(AgentProtocolException.class).hasMessageNotContaining("x".repeat(50));
            mock.verify();
        }
    }
}
