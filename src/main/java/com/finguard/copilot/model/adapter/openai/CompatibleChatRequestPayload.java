package com.finguard.copilot.model.adapter.openai;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.finguard.copilot.config.CopilotProperties;
import com.finguard.copilot.model.port.ModelRequest;

/** Shared by transport and preflight so the measured body is the body actually sent. */
public final class CompatibleChatRequestPayload {
    private static final ObjectMapper MAPPER=new ObjectMapper();
    private CompatibleChatRequestPayload() {}
    public static JsonNode create(CopilotProperties.Model model,ModelRequest request) throws JsonProcessingException {
        ObjectNode payload=MAPPER.createObjectNode();
        payload.put("model",model.modelId());
        var messages=payload.putArray("messages");
        messages.addObject().put("role","system").put("content",request.systemInstruction()+"\nOUTPUT_SCHEMA\n"+request.outputSchemaJson());
        messages.addObject().put("role","user").put("content",request.context());
        payload.put("max_tokens",Math.min(request.maxOutputTokens(),model.outputTokenLimit()));
        payload.put("temperature","zhipu".equalsIgnoreCase(model.provider()) && request.temperature()==0 ? 0.01d : request.temperature());
        if("deepseek".equalsIgnoreCase(model.provider())) payload.putObject("thinking").put("type","disabled");
        var format=payload.putObject("response_format");
        if("qwen".equalsIgnoreCase(model.provider()) || "deepseek".equalsIgnoreCase(model.provider())) format.put("type","json_object");
        else {
            format.put("type","json_schema");
            var schema=format.putObject("json_schema");
            schema.put("name","review_analysis");schema.set("schema",MAPPER.readTree(request.outputSchemaJson()));
        }
        return payload;
    }
}
