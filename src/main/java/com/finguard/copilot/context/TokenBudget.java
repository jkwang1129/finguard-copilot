package com.finguard.copilot.context;

public record TokenBudget(
        int maxInputTokens,
        int maxOutputTokens,
        int systemTokens,
        int factsTokens,
        int knowledgeTokens,
        int reviewerInputTokens,
        int reserveTokens
) {

    public TokenBudget {
        if (maxInputTokens < 1 || maxOutputTokens < 1
                || systemTokens < 1 || factsTokens < 1 || knowledgeTokens < 1
                || reviewerInputTokens < 1 || reserveTokens < 1) {
            throw new IllegalArgumentException("token budgets must be positive");
        }
        long partitionTotal = (long) systemTokens + factsTokens + knowledgeTokens
                + reviewerInputTokens + reserveTokens;
        if (partitionTotal > maxInputTokens) {
            throw new IllegalArgumentException("context partitions exceed max input budget");
        }
    }

    public static TokenBudget phase3Defaults() {
        return new TokenBudget(32_000, 2_000, 4_000, 8_000, 12_000, 2_000, 6_000);
    }
}
