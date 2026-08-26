package com.finguard.copilot.analysis.domain;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public record AnalysisResult(
        int schemaVersion,
        UUID analysisId,
        String reviewTaskSummary,
        String riskRuleExplanation,
        List<BusinessEvidence> businessEvidence,
        List<PolicyCitation> policyCitations,
        AnalysisRecommendation recommendation,
        String reasoningSummary,
        List<String> missingInformation,
        List<String> limitations
) {

    public static final int CURRENT_SCHEMA_VERSION = 1;

    public AnalysisResult {
        if (schemaVersion != CURRENT_SCHEMA_VERSION) {
            throw new IllegalArgumentException("unsupported analysis result schema version");
        }
        Objects.requireNonNull(analysisId, "analysisId must not be null");
        requireText(reviewTaskSummary, "reviewTaskSummary");
        requireText(riskRuleExplanation, "riskRuleExplanation");
        if (businessEvidence == null || businessEvidence.isEmpty()) {
            throw new IllegalArgumentException("businessEvidence must not be empty");
        }
        businessEvidence = List.copyOf(businessEvidence);
        if (policyCitations == null) {
            throw new IllegalArgumentException("policyCitations must not be null");
        }
        policyCitations = List.copyOf(policyCitations);
        Objects.requireNonNull(recommendation, "recommendation must not be null");
        requireText(reasoningSummary, "reasoningSummary");
        missingInformation = copyTextList(missingInformation, "missingInformation");
        limitations = copyTextList(limitations, "limitations");
    }

    public record BusinessEvidence(
            long transactionId,
            long accountId,
            BigDecimal amount,
            String currency,
            String ruleCode,
            String reasonCode,
            List<String> sourcePaths
    ) {

        public BusinessEvidence {
            if (transactionId < 1 || accountId < 1) {
                throw new IllegalArgumentException("business evidence identifiers must be positive");
            }
            Objects.requireNonNull(amount, "amount must not be null");
            if (amount.signum() < 0) {
                throw new IllegalArgumentException("amount must not be negative");
            }
            amount = new BigDecimal(amount.stripTrailingZeros().toPlainString());
            requireText(currency, "currency");
            requireText(ruleCode, "ruleCode");
            requireText(reasonCode, "reasonCode");
            if (sourcePaths == null || sourcePaths.isEmpty()) {
                throw new IllegalArgumentException("sourcePaths must not be empty");
            }
            sourcePaths = copyTextList(sourcePaths, "sourcePaths");
        }
    }

    public record PolicyCitation(
            String documentId,
            String version,
            String sourcePath,
            String excerpt
    ) {

        public PolicyCitation {
            requireText(documentId, "documentId");
            requireText(version, "version");
            requireText(sourcePath, "sourcePath");
            requireText(excerpt, "excerpt");
        }
    }

    private static List<String> copyTextList(List<String> values, String name) {
        if (values == null || values.stream().anyMatch(value -> value == null || value.isBlank())) {
            throw new IllegalArgumentException(name + " must not be null or contain blank values");
        }
        return List.copyOf(values);
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
