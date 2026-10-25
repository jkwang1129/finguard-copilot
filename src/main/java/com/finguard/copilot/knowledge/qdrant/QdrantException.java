package com.finguard.copilot.knowledge.qdrant;

public final class QdrantException extends RuntimeException {

    public static final String KNOWLEDGE_UNAVAILABLE = "KNOWLEDGE_UNAVAILABLE";

    private final String code;

    public QdrantException(String message, Throwable cause) {
        super(message, cause);
        this.code = KNOWLEDGE_UNAVAILABLE;
    }

    public QdrantException(String message) {
        this(message, null);
    }

    public String code() {
        return code;
    }
}
