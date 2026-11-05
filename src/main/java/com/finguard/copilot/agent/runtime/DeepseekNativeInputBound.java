package com.finguard.copilot.agent.runtime;

import java.nio.charset.StandardCharsets;
import java.net.URI;
import java.util.*;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import com.finguard.copilot.agent.model.*;
import com.finguard.copilot.agent.tool.ToolRegistry;
import com.finguard.copilot.common.error.*;
import com.finguard.copilot.config.CopilotProperties;
import com.finguard.copilot.governance.*;

/** Public V4.1 byte-BPE structural bound; only the grammar reviewed in the native-bound contract. */
public final class DeepseekNativeInputBound {
    public static final String VERSION="deepseek-v41-native-tools-byte-bound-v1";
    public static final String SCHEMA_SHA256="e0401e9bcd95bd5290d8184e89b2aa97ed164f6fc697f49b3af8780931c7d80f";
    public static final String EVIDENCE_SHA256=CanonicalJson.hash(Map.of("version",VERSION,
        "recipe",DeepseekTextTokenBound.RECIPE_REVISION,"tokenizer",DeepseekTextTokenBound.TOKENIZER_SHA256,
        "schema",SCHEMA_SHA256,"staticAllowance",8192,"messageAllowance",128,"callAllowance",512));
    private final ObjectMapper json=new ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    public int upperBound(CopilotProperties.Model model,AgentTurnRequest request,ToolRegistry registry){
        if(!"deepseek".equals(model.provider()) || !"deepseek-flash".equals(model.modelId())
            || !URI.create("https://api.deepseek.com").equals(model.baseUrl()) || !"/chat/completions".equals(model.chatCompletionsPath()))throw unsupported();
        try(var resource=getClass().getResourceAsStream("/schema/agent-tools-v1.schema.json")){
            if(resource==null || !java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(resource.readAllBytes())).equals(SCHEMA_SHA256))throw unsupported();
        }catch(java.io.IOException|java.security.NoSuchAlgorithmException e){throw unsupported();}
        var messages=request.messages();checkUnicode(messages);
        if(messages.size()<2 || messages.size()>7 || request.outputCap()>model.outputTokenLimit())throw unsupported();
        textMessage(messages.get(0),"system");textMessage(messages.get(1),"user");
        int calls=0;
        if(request.callOrdinal()==1){if(!request.allowTools() || messages.size()!=2)throw unsupported();}
        else if(messages.size()==3){textMessage(messages.get(2),"user");}
        else {
            if(messages.size()<4)throw unsupported();
            var assistant=messages.get(2);fields(assistant,Set.of("role","tool_calls","content"));
            if(!"assistant".equals(assistant.path("role").asText()) || assistant.hasNonNull("content") && (!assistant.path("content").isTextual() || !assistant.path("content").asText().isEmpty()))throw unsupported();
            var batch=assistant.path("tool_calls");if(!batch.isArray() || batch.isEmpty() || batch.size()>4 || messages.size()!=3+batch.size())throw unsupported();
            Set<String> ids=new HashSet<>();
            for(int i=0;i<batch.size();i++){
                var call=batch.get(i);fields(call,Set.of("id","type","function"));var function=call.path("function");fields(function,Set.of("name","arguments"));
                String id=call.path("id").asText();if(!call.path("id").isTextual() || !id.matches("[A-Za-z0-9_-]{1,128}") || !ids.add(id) || !"function".equals(call.path("type").asText()) || !function.path("arguments").isTextual())throw unsupported();
                try{var args=json.readTree(function.path("arguments").asText());checkUnicode(args);registry.validate(function.path("name").asText(),args);}catch(java.io.IOException e){throw unsupported();}
                var result=messages.get(i+3);fields(result,Set.of("role","tool_call_id","content"));
                if(!"tool".equals(result.path("role").asText()) || !result.path("tool_call_id").isTextual() || !id.equals(result.path("tool_call_id").asText()) || !result.path("content").isTextual())throw unsupported();
            }
            calls=batch.size();
        }
        try{
            int wire=bytes(CompatibleAgentModelGateway.payload(model,request,registry).toString());
            int upper=Math.addExact(wire,Math.addExact(8192,Math.addExact(Math.multiplyExact(128,messages.size()),Math.multiplyExact(512,calls))));
            if(upper>Math.min(32000,model.inputTokenLimit()))throw budget();return upper;
        }catch(ArithmeticException e){throw budget();}
    }
    private void textMessage(JsonNode message,String role){fields(message,Set.of("role","content"));if(!role.equals(message.path("role").asText()) || !message.path("content").isTextual())throw unsupported();}
    private void fields(JsonNode node,Set<String> allowed){if(!node.isObject())throw unsupported();node.fieldNames().forEachRemaining(key->{if(!allowed.contains(key))throw unsupported();});}
    private void checkUnicode(JsonNode node){
        if(node==null)throw unsupported();
        if(node.isTextual())bytes(node.asText());
        else if(node.isObject())node.fields().forEachRemaining(e->{bytes(e.getKey());checkUnicode(e.getValue());});
        else if(node.isArray())node.forEach(this::checkUnicode);
    }
    private int bytes(String value){try{return StandardCharsets.UTF_8.newEncoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT).onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT).encode(java.nio.CharBuffer.wrap(value)).remaining();}catch(java.nio.charset.CharacterCodingException e){throw unsupported();}}
    private static CopilotException unsupported(){return new CopilotException(ErrorCode.MODEL_CONFIGURATION_INVALID,"Agent native model or request grammar unsupported");}
    private static CopilotException budget(){return new CopilotException(ErrorCode.CONTEXT_BUDGET_EXCEEDED,"Agent native context budget exceeded");}
}
