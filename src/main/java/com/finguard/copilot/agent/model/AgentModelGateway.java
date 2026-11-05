package com.finguard.copilot.agent.model;
@FunctionalInterface
public interface AgentModelGateway { AgentTurnResponse complete(AgentTurnRequest request); }
