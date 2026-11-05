package com.finguard.copilot.agent.model;
import java.util.List;
import com.finguard.copilot.model.port.ModelUsage;
public sealed interface AgentTurnResponse permits AgentTurnResponse.FinalCandidate,AgentTurnResponse.ToolBatch {
    ModelUsage usage();
    String providerRequestHash();
    record FinalCandidate(String content,ModelUsage usage,String providerRequestHash) implements AgentTurnResponse {
        @Override public String toString(){return "FinalCandidate[unvalidated]";}
    }
    record ToolBatch(List<AgentToolCall> calls,ModelUsage usage,String providerRequestHash) implements AgentTurnResponse {
        public ToolBatch {calls=List.copyOf(calls);}
    }
}
