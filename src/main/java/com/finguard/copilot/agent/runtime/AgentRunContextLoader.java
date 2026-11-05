package com.finguard.copilot.agent.runtime;
import java.util.UUID;
@FunctionalInterface
public interface AgentRunContextLoader {AgentRunContext load(UUID analysisId);}
