package com.finguard.copilot.coreclient.http;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import com.finguard.copilot.coreclient.port.ReviewTaskContext;

record CoreReviewTaskContextResponse(
        ReviewTask reviewTask,
        Transaction transaction,
        Reconciliation reconciliation,
        List<RiskFact> riskFacts,
        AccountSummary accountSummary,
        Instant capturedAt
) {

    ReviewTaskContext toPort() {
        return new ReviewTaskContext(
                reviewTask.toPort(),
                transaction.toPort(),
                reconciliation.toPort(),
                riskFacts.stream().map(RiskFact::toPort).toList(),
                accountSummary.toPort(),
                capturedAt
        );
    }

    record ReviewTask(
            Long id,
            Long version,
            String status,
            String ruleCode,
            String reasonCode,
            Instant createdAt
    ) {

        ReviewTaskContext.ReviewTask toPort() {
            return new ReviewTaskContext.ReviewTask(id, version, status, ruleCode, reasonCode, createdAt);
        }
    }

    record Transaction(
            Long id,
            Long accountId,
            BigDecimal amount,
            String currency,
            Instant occurredAt,
            String sourceType
    ) {

        ReviewTaskContext.Transaction toPort() {
            return new ReviewTaskContext.Transaction(id, accountId, amount, currency, occurredAt, sourceType);
        }
    }

    record Reconciliation(String resultType, String matchMethod, String reasonCode) {

        ReviewTaskContext.Reconciliation toPort() {
            return new ReviewTaskContext.Reconciliation(resultType, matchMethod, reasonCode);
        }
    }

    record RiskFact(String code, String value, String source) {

        ReviewTaskContext.RiskFact toPort() {
            return new ReviewTaskContext.RiskFact(code, value, source);
        }
    }

    record AccountSummary(String status, String displayName) {

        ReviewTaskContext.AccountSummary toPort() {
            return new ReviewTaskContext.AccountSummary(status, displayName);
        }
    }
}
