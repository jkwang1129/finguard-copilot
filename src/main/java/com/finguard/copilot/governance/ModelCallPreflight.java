package com.finguard.copilot.governance;

import java.util.List;
import java.util.UUID;
import com.finguard.copilot.context.FactSnapshot;
import com.finguard.copilot.knowledge.retrieval.KnowledgeEvidence;
import com.finguard.copilot.model.port.ModelRequest;

@FunctionalInterface
public interface ModelCallPreflight {
    default boolean managesReservation() {return false;}
    default void outcomeUnknown(UUID analysisId) {}
    default void outcomeUnknown(UUID analysisId,com.finguard.copilot.model.port.ModelUsage usage) {outcomeUnknown(analysisId);}
    default void afterCall(UUID analysisId) {}
    ModelRequest prepare(UUID analysisId, FactSnapshot snapshot, ModelRequest request, List<KnowledgeEvidence> evidence, String retrievedIndexVersion);
}
