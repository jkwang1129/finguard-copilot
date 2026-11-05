package com.finguard.copilot.agent.budget;
import java.util.UUID;
public record CallClaim(UUID analysisId,int callOrdinal,String manifestHash) {}
