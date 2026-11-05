package com.finguard.copilot.agent.tool;

import java.io.IOException;
import java.text.Normalizer;
import java.util.*;
import com.fasterxml.jackson.databind.*;
import com.finguard.copilot.common.error.*;
import com.finguard.copilot.guardrail.UntrustedInstructionGuard;
import com.finguard.copilot.analysis.domain.AnalysisRecommendation;

/** The published resource is the shared authority for names and argument limits. */
public final class ToolRegistry {
    private final Map<String,JsonNode> tools=new LinkedHashMap<>();
    public ToolRegistry() {
        try(var input=ToolRegistry.class.getResourceAsStream("/schema/agent-tools-v1.schema.json")) {
            if(input==null) throw new IllegalStateException("Tool schema unavailable");
            for(var tool:new ObjectMapper().readTree(input).path("tools")) {
                tools.put(tool.path("name").asText(),tool.deepCopy());
            }
            if(tools.size()!=3) throw new IllegalStateException("Tool registry is invalid");
        } catch(IOException exception) {throw new IllegalStateException("Tool schema unavailable");}
    }
    public Set<String> names(){return Collections.unmodifiableSet(tools.keySet());}
    public List<JsonNode> schemas(){return tools.values().stream().<JsonNode>map(JsonNode::deepCopy).toList();}
    public void validate(String name,JsonNode arguments) {
        var tool=tools.get(name);
        if(tool==null || arguments==null || !arguments.isObject()) throw rejected();
        var schema=tool.path("parameters");
        var properties=schema.path("properties");
        for(var required:schema.path("required")) if(!arguments.has(required.asText())) throw rejected();
        var fields=arguments.fields();
        while(fields.hasNext()) {
            var entry=fields.next();
            var definition=properties.path(entry.getKey());
            var value=entry.getValue();
            if(definition.isMissingNode()) throw rejected();
            switch(definition.path("type").asText()) {
                case "string" -> {
                    if(!value.isTextual() || value.asText().isBlank()
                        || value.asText().codePointCount(0,value.asText().length())>definition.path("maxLength").asInt()) throw rejected();
                    checkUntrusted(value.asText());
                }
                case "integer" -> {
                    if(!value.isIntegralNumber() || !value.canConvertToInt()
                        || value.intValue()<definition.path("minimum").asInt()
                        || value.intValue()>definition.path("maximum").asInt()) throw rejected();
                }
                default -> throw rejected();
            }
        }
    }
    public static void checkUntrusted(String text) {
        var normalized=Normalizer.normalize(text,Normalizer.Form.NFKC).replaceAll("\\p{Cf}","");
        if(normalized.matches("(?is).*(?:https?://|ftp://|file:|www\\.|bearer\\s|api[_-]?key\\s*[=:]).*")) throw rejected();
        try {
            new UntrustedInstructionGuard().validate(normalized,List.of(),AnalysisRecommendation.CONFIRMED);
        } catch(CopilotException exception) {throw rejected();}
    }
    static CopilotException rejected(){return new CopilotException(ErrorCode.INVALID_REQUEST,"Tool input or output rejected");}
}
