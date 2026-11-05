package com.finguard.copilot.agent.tool;
import com.fasterxml.jackson.databind.JsonNode;
public record ToolResult(String toolName,String source,JsonNode data,String checksum) {
    public ToolResult {data=data.deepCopy();}
    @Override public JsonNode data(){return data.deepCopy();}
}
