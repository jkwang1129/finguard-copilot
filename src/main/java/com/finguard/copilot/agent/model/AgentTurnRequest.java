package com.finguard.copilot.agent.model;
import java.util.UUID;
import com.fasterxml.jackson.databind.node.ArrayNode;
public record AgentTurnRequest(UUID analysisId,int callOrdinal,ArrayNode messages,boolean allowTools,int outputCap) {
    public AgentTurnRequest {
        if(analysisId==null || callOrdinal<1 || callOrdinal>2 || callOrdinal==2 && allowTools
            || messages==null || messages.size()<2 || outputCap<1 || outputCap>2000) throw new IllegalArgumentException("Agent request invalid");
        messages=messages.deepCopy();
    }
    @Override public ArrayNode messages(){return messages.deepCopy();}
    @Override public String toString(){return "AgentTurnRequest[ordinal="+callOrdinal+",allowTools="+allowTools+"]";}
}
