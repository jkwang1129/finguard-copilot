package com.finguard.copilot.coreclient.port;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record ReviewTaskContext(
        ReviewTask reviewTask,
        Transaction transaction,
        Reconciliation reconciliation,
        List<RiskFact> riskFacts,
        AccountSummary accountSummary,
        Instant capturedAt
) {

    public ReviewTaskContext {
        requireNonNull(reviewTask, "reviewTask");
        requireNonNull(transaction, "transaction");
        requireNonNull(reconciliation, "reconciliation");
        requireNonNull(accountSummary, "accountSummary");
        requireNonNull(capturedAt, "capturedAt");
        if (riskFacts == null || riskFacts.stream().anyMatch(fact -> fact == null)) {
            throw new IllegalArgumentException("riskFacts must not be null or contain null values");
        }
        riskFacts = List.copyOf(riskFacts);
    }

    public record ReviewTask(
            long id,
            long version,
            String status,
            String ruleCode,
            String reasonCode,
            Instant createdAt
    ) {

        public ReviewTask {
            requirePositive(id, "id");
            if (version < 0) {
                throw new IllegalArgumentException("version must not be negative");
            }
            requireText(status, "status");
            if (ruleCode != null && ruleCode.isBlank()) {
                throw new IllegalArgumentException("ruleCode must be null or non-blank");
            }
            requireText(reasonCode, "reasonCode");
            requireNonNull(createdAt, "createdAt");
        }
    }

    public record Transaction(
            long id,
            long accountId,
            BigDecimal amount,
            String currency,
            Instant occurredAt,
            String sourceType
    ) {

        public Transaction {
            requirePositive(id, "id");
            requirePositive(accountId, "accountId");
            requireNonNull(amount, "amount");
            if (amount.signum() < 0) {
                throw new IllegalArgumentException("amount must not be negative");
            }
            requireText(currency, "currency");
            requireNonNull(occurredAt, "occurredAt");
            requireText(sourceType, "sourceType");
        }
    }

    public record Reconciliation(String resultType, String matchMethod, String reasonCode) {

        public Reconciliation {
            requireText(resultType, "resultType");
            requireText(matchMethod, "matchMethod");
            requireText(reasonCode, "reasonCode");
        }
    }

    public record RiskFact(String code, String value, String source) {

        public RiskFact {
            requireText(code, "code");
            requireText(value, "value");
            requireText(source, "source");
        }
    }

    public record AccountSummary(String status, String displayName) {

        public AccountSummary {
            requireText(status, "status");
            requireText(displayName, "displayName");
        }
    }

    private static void requirePositive(long value, String name) {
        if (value < 1) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }

    private static void requireNonNull(Object value, String name) {
        if (value == null) {
            throw new IllegalArgumentException(name + " must not be null");
        }
    }
}
