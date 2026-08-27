package com.finguard.copilot.knowledge.index;

public record RagEvaluationResult(
        String indexVersion,
        double recallAtK,
        double citationPrecision,
        double noAnswerCorrectness,
        String evaluatorVersion,
        int caseCount
) {

    public RagEvaluationResult {
        requireText(indexVersion, "indexVersion");
        requireScore(recallAtK, "recallAtK");
        requireScore(citationPrecision, "citationPrecision");
        requireScore(noAnswerCorrectness, "noAnswerCorrectness");
        requireText(evaluatorVersion, "evaluatorVersion");
        if (caseCount < 1) {
            throw new IllegalArgumentException("caseCount must be positive");
        }
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }

    private static void requireScore(double value, String name) {
        if (!Double.isFinite(value) || value < 0.0 || value > 1.0) {
            throw new IllegalArgumentException(name + " must be between 0 and 1");
        }
    }
}
