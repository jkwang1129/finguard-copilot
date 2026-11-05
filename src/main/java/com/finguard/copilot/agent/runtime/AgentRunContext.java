package com.finguard.copilot.agent.runtime;
import java.util.UUID;
import com.finguard.copilot.context.FactSnapshot;
public record AgentRunContext(UUID analysisId,String reviewer,FactSnapshot snapshot,String indexVersion) {}
