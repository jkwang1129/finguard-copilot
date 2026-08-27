package com.finguard.copilot.knowledge.retrieval;

public class KnowledgeRetrievalException extends RuntimeException {

    public static final String KNOWLEDGE_UNAVAILABLE = "KNOWLEDGE_UNAVAILABLE";

    public KnowledgeRetrievalException(String message) {
        super(message);
    }

    public KnowledgeRetrievalException(String message, Throwable cause) {
        super(message, cause);
    }
}
