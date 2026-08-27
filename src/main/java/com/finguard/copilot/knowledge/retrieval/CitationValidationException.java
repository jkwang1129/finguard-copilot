package com.finguard.copilot.knowledge.retrieval;

public class CitationValidationException extends RuntimeException {

    public static final String CITATION_VALIDATION_FAILED = "CITATION_VALIDATION_FAILED";

    public CitationValidationException(String message) {
        super(CITATION_VALIDATION_FAILED + ": " + message);
    }
}
