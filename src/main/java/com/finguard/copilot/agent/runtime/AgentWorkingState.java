package com.finguard.copilot.agent.runtime;
import java.util.*;
import com.finguard.copilot.agent.model.AgentToolCall;
import com.finguard.copilot.agent.tool.ToolResult;
import com.finguard.copilot.analysis.domain.AnalysisResult;
import com.finguard.copilot.knowledge.retrieval.KnowledgeEvidence;

/** Only validated evidence, tool selections/results and controlled errors; never raw model candidates or reasoning. */
public record AgentWorkingState(AgentNode node,List<KnowledgeEvidence> evidence,List<AgentToolCall> plannedTools,
        List<ToolResult> toolResults,int retrievalAttempts,int toolsUsed,String repairCode,AnalysisResult validatedResult) {
    public AgentWorkingState {
        Objects.requireNonNull(node);evidence=List.copyOf(evidence);plannedTools=List.copyOf(plannedTools);toolResults=List.copyOf(toolResults);
        if(retrievalAttempts<0 || retrievalAttempts>2 || toolsUsed<0 || toolsUsed>4 || plannedTools.size()>4
            || toolResults.size()>plannedTools.size() || repairCode!=null && !Set.of("MODEL_OUTPUT_INVALID","OUTPUT_SCHEMA_INVALID").contains(repairCode)) {
            throw new IllegalArgumentException("Agent working state invalid");
        }
    }
    public static AgentWorkingState initial(){return new AgentWorkingState(AgentNode.RETRIEVE,List.of(),List.of(),List.of(),0,0,null,null);}
    public AgentWorkingState withNode(AgentNode next){return new AgentWorkingState(next,evidence,plannedTools,toolResults,retrievalAttempts,toolsUsed,repairCode,validatedResult);}
    public AgentWorkingState planned(List<AgentToolCall> calls){return new AgentWorkingState(AgentNode.TOOLS,evidence,calls,List.of(),retrievalAttempts,toolsUsed,null,null);}
    public AgentWorkingState candidate(AnalysisResult result){return new AgentWorkingState(AgentNode.COMPLETE,evidence,plannedTools,toolResults,retrievalAttempts,toolsUsed,null,result);}
}
