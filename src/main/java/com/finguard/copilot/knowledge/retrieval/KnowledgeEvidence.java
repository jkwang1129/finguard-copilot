package com.finguard.copilot.knowledge.retrieval;

import com.finguard.copilot.knowledge.domain.KnowledgeCitation;

public record KnowledgeEvidence(KnowledgeCitation citation, String excerpt) {

    public KnowledgeEvidence {
        if (citation == null) {
            throw new IllegalArgumentException("citation must not be null");
        }
        if (excerpt == null || excerpt.isBlank()) {
            throw new IllegalArgumentException("excerpt must not be blank");
        }
    }
}
