package com.finguard.copilot.agent.checkpoint;
import java.time.Instant;
import java.util.UUID;
import com.finguard.copilot.agent.runtime.AgentWorkingState;
public record AgentCheckpoint(UUID analysisId,int schemaVersion,String workflowVersion,long revision,String node,
        AgentWorkingState working,String owner,Instant leaseUntil,boolean waiting,boolean humanApproved,
        Instant waitExpiresAt,String snapshotHash,String indexVersion,Instant snapshotExpiresAt,int modelCalls,int toolCalls) {}
