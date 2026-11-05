package com.finguard.copilot.agent.model;

import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.finguard.copilot.agent.tool.ToolRegistry;
import com.finguard.copilot.common.error.*;
import com.finguard.copilot.config.CopilotProperties;
import com.finguard.copilot.governance.CanonicalJson;
import com.finguard.copilot.model.port.ModelUsage;
import org.springframework.http.*;
import org.springframework.web.client.*;

/** Native tool envelope, separate from the P4 final-JSON-only adapter. Never forwards reasoning_content. */
public final class CompatibleAgentModelGateway implements AgentModelGateway {
    private final CopilotProperties.Model model;
    private final RestClient client;
    private final ToolRegistry registry;
    private final ObjectMapper json=new ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
    public CompatibleAgentModelGateway(CopilotProperties.Model model,RestClient.Builder builder,ToolRegistry registry){
        if(!"deepseek".equals(model.provider()) || !model.apiKey().isPresent() || !model.isSafeBaseUrl()) throw new IllegalArgumentException("Agent model configuration invalid");
        this.model=model;this.registry=registry;
        this.client=builder.baseUrl(model.baseUrl().toString()).defaultHeader(HttpHeaders.AUTHORIZATION,"Bearer "+model.apiKey().value()).build();
    }
    public static ObjectNode payload(CopilotProperties.Model model,AgentTurnRequest request,ToolRegistry registry){
        var json=new ObjectMapper();var payload=json.createObjectNode();
        payload.put("model",model.modelId());payload.set("messages",request.messages());
        payload.put("max_tokens",Math.min(request.outputCap(),model.outputTokenLimit()));
        payload.putObject("thinking").put("type","disabled");payload.put("stream",false);
        payload.put("tool_choice",request.allowTools()?"auto":"none");
        var tools=payload.putArray("tools");
        for(var schema:registry.schemas()){
            var function=tools.addObject().put("type","function").putObject("function");
            function.put("name",schema.path("name").asText());function.put("description",schema.path("description").asText());
            function.set("parameters",schema.path("parameters"));
        }
        if(!request.allowTools()) payload.putObject("response_format").put("type","json_object");
        return payload;
    }
    @Override public AgentTurnResponse complete(AgentTurnRequest request){
        Instant started=Instant.now();
        try{
            return client.post().uri(model.chatCompletionsPath()).contentType(MediaType.APPLICATION_JSON)
                .body(payload(model,request,registry).toString()).exchange((sent,response)->{
                    if(!response.getStatusCode().is2xxSuccessful()){
                        int status=response.getStatusCode().value();
                        throw new AgentProtocolException(status==429?ErrorCode.MODEL_RATE_LIMITED:
                            status==408||status==504?ErrorCode.MODEL_TIMEOUT:ErrorCode.MODEL_UNAVAILABLE,null);
                    }
                    byte[] body=response.getBody().readNBytes(65537);
                    if(body.length>65536) throw invalid(null);
                    return decode(body,request,Duration.between(started,Instant.now()));
                });
        }catch(AgentProtocolException exception){throw exception;}
        catch(ResourceAccessException exception){throw new AgentProtocolException(ErrorCode.MODEL_TIMEOUT,null);}
        catch(RestClientException exception){throw new AgentProtocolException(ErrorCode.MODEL_UNAVAILABLE,null);}
    }
    private AgentTurnResponse decode(byte[] body,AgentTurnRequest request,Duration elapsed){
        ModelUsage usage=null;
        try{
            var root=json.readTree(body);var reported=root.path("usage");
            var input=reported.path("prompt_tokens");var output=reported.path("completion_tokens");
            if(!input.isIntegralNumber() || !input.canConvertToInt() || !output.isIntegralNumber() || !output.canConvertToInt()) throw invalid(null);
            usage=new ModelUsage(input.intValue(),output.intValue(),elapsed);
            var choices=root.path("choices");
            if(!choices.isArray() || choices.size()!=1) throw invalid(usage);
            var choice=choices.get(0);var message=choice.path("message");var calls=message.path("tool_calls");
            if(!"assistant".equals(message.path("role").asText())
                || message.hasNonNull("function_call") && !message.path("function_call").isEmpty()
                || message.hasNonNull("reasoning_content") && !message.path("reasoning_content").asText().isBlank()
                || !calls.isMissingNode() && !calls.isNull() && !calls.isArray()) throw invalid(usage);
            String providerHash=CanonicalJson.sha256(root.path("id").asText("not-reported"));
            if(calls.isArray() && !calls.isEmpty()){
                if(!request.allowTools() || calls.size()>4 || !"tool_calls".equals(choice.path("finish_reason").asText())) throw invalid(usage);
                var ids=new HashSet<String>();var parsed=new ArrayList<AgentToolCall>();
                for(var call:calls){
                    if(!"function".equals(call.path("type").asText()) || !call.path("function").path("arguments").isTextual()) throw invalid(usage);
                    String id=call.path("id").asText();
                    if(!ids.add(id)) throw invalid(usage);
                    var function=call.path("function");
                    parsed.add(new AgentToolCall(id,function.path("name").asText(),
                        json.readTree(function.path("arguments").asText())));
                }
                return new AgentTurnResponse.ToolBatch(parsed,usage,providerHash);
            }
            var content=message.path("content");
            if(!content.isTextual() || content.asText().isBlank() || !"stop".equals(choice.path("finish_reason").asText())) throw invalid(usage);
            return new AgentTurnResponse.FinalCandidate(content.asText(),usage,providerHash);
        }catch(AgentProtocolException exception){throw exception;}
        catch(java.io.IOException|IllegalArgumentException|CopilotException exception){throw invalid(usage);}
    }
    private static AgentProtocolException invalid(ModelUsage usage){return new AgentProtocolException(ErrorCode.MODEL_OUTPUT_INVALID,usage);}
}
