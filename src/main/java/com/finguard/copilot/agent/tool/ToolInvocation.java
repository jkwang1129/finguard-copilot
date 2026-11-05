package com.finguard.copilot.agent.tool;
import com.fasterxml.jackson.databind.JsonNode;
public record ToolInvocation(String toolName,JsonNode arguments) {
    public ToolInvocation {
        if(toolName==null || toolName.length()>64 || arguments==null || arguments.toString().length()>4096) {
            throw ToolRegistry.rejected();
        }
        arguments=arguments.deepCopy();
    }
    @Override public JsonNode arguments(){return arguments.deepCopy();}
}
