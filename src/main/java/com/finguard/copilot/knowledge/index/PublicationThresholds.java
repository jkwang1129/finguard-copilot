package com.finguard.copilot.knowledge.index;

public record PublicationThresholds(
        double minimumRecallAtK,
        double minimumCitationPrecision,
        double minimumNoAnswerCorrectness
) {

    public PublicationThresholds {
        requireScore(minimumRecallAtK, "minimumRecallAtK");
        requireScore(minimumCitationPrecision, "minimumCitationPrecision");
        requireScore(minimumNoAnswerCorrectness, "minimumNoAnswerCorrectness");
    }

    public boolean accepts(RagEvaluationResult result) {
        return result.recallAtK() >= minimumRecallAtK
                && result.citationPrecision() >= minimumCitationPrecision
                && result.noAnswerCorrectness() >= minimumNoAnswerCorrectness;
    }

    private static void requireScore(double value, String name) {
        if (!Double.isFinite(value) || value < 0.0 || value > 1.0) {
            throw new IllegalArgumentException(name + " must be between 0 and 1");
        }
    }
}
