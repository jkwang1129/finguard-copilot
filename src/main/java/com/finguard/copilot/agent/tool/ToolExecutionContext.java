package com.finguard.copilot.agent.tool;
import java.util.UUID;
/** Internal server-injected scope, never bind this record from a model's arguments. */
public record ToolExecutionContext(UUID analysisId,String reviewer,String snapshotHash,String indexVersion,String stepKey,Long mcpRevision,String mcpCapabilityHash,com.finguard.copilot.agent.runtime.AgentExecutionFence executionFence) {
    public ToolExecutionContext(UUID id,String reviewer,String hash,String index,String step,Long revision,String capability){this(id,reviewer,hash,index,step,revision,capability,null);}
    public ToolExecutionContext(UUID id,String reviewer,String hash,String index,String step){this(id,reviewer,hash,index,step,null,null);}
    public ToolExecutionContext {
        if(analysisId==null || reviewer==null || reviewer.isBlank() || snapshotHash==null
            || !snapshotHash.matches("[a-f0-9]{64}") || stepKey==null || !stepKey.matches("[A-Za-z0-9:_-]{1,100}")
            || mcpRevision!=null && executionFence!=null || (mcpRevision==null)!=(mcpCapabilityHash==null) || mcpRevision!=null && (mcpRevision<0 || !mcpCapabilityHash.matches("[a-f0-9]{64}") || !stepKey.startsWith("mcp-"))) {
            throw ToolRegistry.rejected();
        }
    }
}
