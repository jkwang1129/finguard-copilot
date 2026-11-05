package com.finguard.copilot.agent.model;
import com.fasterxml.jackson.databind.JsonNode;
import com.finguard.copilot.agent.tool.ToolRegistry;
public record AgentToolCall(String callId,String toolName,JsonNode arguments) {
    public AgentToolCall {
        if(callId==null || !callId.matches("[A-Za-z0-9_-]{1,128}")) throw new IllegalArgumentException("Tool call ID invalid");
        new ToolRegistry().validate(toolName,arguments);
        arguments=arguments.deepCopy();
    }
    @Override public JsonNode arguments(){return arguments.deepCopy();}
    @Override public String toString(){return "AgentToolCall[name="+toolName+"]";}
}
