package com.finguard.copilot.guardrail;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.finguard.copilot.analysis.domain.AnalysisRecommendation;
import com.finguard.copilot.analysis.domain.AnalysisResult;
import com.finguard.copilot.common.error.CopilotException;
import com.finguard.copilot.common.error.ErrorCode;
import com.finguard.copilot.context.FactSnapshot;
import com.finguard.copilot.context.SnapshotCodec;
import com.finguard.copilot.coreclient.port.ReviewTaskContext;
import com.finguard.copilot.coreclient.port.ReviewTaskContext.AccountSummary;
import com.finguard.copilot.coreclient.port.ReviewTaskContext.Reconciliation;
import com.finguard.copilot.coreclient.port.ReviewTaskContext.ReviewTask;
import com.finguard.copilot.coreclient.port.ReviewTaskContext.RiskFact;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FactConsistencyValidatorTest {

    private static final UUID ANALYSIS_ID = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final Instant CAPTURED_AT = Instant.parse("2026-08-25T01:02:03.456789Z");

    private final FactSnapshot snapshot = new SnapshotCodec().capture(new ReviewTaskContext(
            new ReviewTask(101, 0, "PENDING", "HIGH_AMOUNT", "AMOUNT_THRESHOLD_EXCEEDED",
                    CAPTURED_AT.minusSeconds(60)),
            new ReviewTaskContext.Transaction(202, 303, new BigDecimal("120000.00"), "CNY",
                    CAPTURED_AT.minusSeconds(30), "CSV_IMPORT"),
            new Reconciliation("SUSPICIOUS", "AMOUNT_AND_TIME", "REQUIRES_REVIEW"),
            List.of(new RiskFact("HIGH_AMOUNT", "120000.00", "TRANSACTION")),
            new AccountSummary("ACTIVE", "Primary"),
            CAPTURED_AT));

    @Test
    void acceptsCopiedFactsAndInsufficientEvidenceWithoutPolicyCitations() {
        assertThatCode(() -> new FactConsistencyValidator().validate(
                ANALYSIS_ID, snapshot, validResult(AnalysisRecommendation.INSUFFICIENT_EVIDENCE)))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsChangedAmount() {
        assertFails(validResult(AnalysisRecommendation.INSUFFICIENT_EVIDENCE,
                new BigDecimal("120001"), "HIGH_AMOUNT", List.of("transaction.id")));
    }

    @Test
    void rejectsChangedTransactionId() {
        assertFails(validResult(201, 303, "CNY", "AMOUNT_THRESHOLD_EXCEEDED",
                new BigDecimal("120000"), "HIGH_AMOUNT", List.of("transaction.id")));
    }

    @Test
    void rejectsChangedAccountId() {
        assertFails(validResult(202, 304, "CNY", "AMOUNT_THRESHOLD_EXCEEDED",
                new BigDecimal("120000"), "HIGH_AMOUNT", List.of("transaction.accountId")));
    }

    @Test
    void rejectsChangedCurrency() {
        assertFails(validResult(202, 303, "USD", "AMOUNT_THRESHOLD_EXCEEDED",
                new BigDecimal("120000"), "HIGH_AMOUNT", List.of("transaction.currency")));
    }

    @Test
    void rejectsChangedReasonCode() {
        assertFails(validResult(202, 303, "CNY", "INVENTED_REASON",
                new BigDecimal("120000"), "HIGH_AMOUNT", List.of("reviewTask.reasonCode")));
    }

    @Test
    void rejectsInventedRule() {
        assertFails(validResult(AnalysisRecommendation.INSUFFICIENT_EVIDENCE,
                new BigDecimal("120000"), "INVENTED_RULE", List.of("transaction.id")));
    }

    @Test
    void rejectsInventedFactPath() {
        assertFails(validResult(AnalysisRecommendation.INSUFFICIENT_EVIDENCE,
                new BigDecimal("120000"), "HIGH_AMOUNT", List.of("transaction.notPresent")));
    }

    @ParameterizedTest
    @EnumSource(value = AnalysisRecommendation.class, names = {"CONFIRMED", "IGNORED"})
    void rejectsDirectionalRecommendationWithoutPolicyCitation(AnalysisRecommendation recommendation) {
        assertFails(validResult(recommendation));
    }

    @Test
    void rejectsAnalysisIdMismatch() {
        assertThatThrownBy(() -> new FactConsistencyValidator().validate(
                UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"),
                snapshot,
                validResult(AnalysisRecommendation.INSUFFICIENT_EVIDENCE)))
                .isInstanceOf(CopilotException.class)
                .extracting(exception -> ((CopilotException) exception).code())
                .isEqualTo(ErrorCode.FACT_VALIDATION_FAILED);
    }

    @Test
    void rejectsSyntheticRuleWhenSnapshotRuleCodeIsMissing() {
        FactSnapshot missingRuleSnapshot = new SnapshotCodec().capture(new ReviewTaskContext(
                new ReviewTask(101, 0, "PENDING", null, "AMOUNT_THRESHOLD_EXCEEDED",
                        CAPTURED_AT.minusSeconds(60)),
                new ReviewTaskContext.Transaction(202, 303, new BigDecimal("120000.00"), "CNY",
                        CAPTURED_AT.minusSeconds(30), "CSV_IMPORT"),
                new Reconciliation("SUSPICIOUS", "AMOUNT_AND_TIME", "REQUIRES_REVIEW"),
                List.of(new RiskFact("HIGH_AMOUNT", "120000.00", "TRANSACTION")),
                new AccountSummary("ACTIVE", "Primary"),
                CAPTURED_AT));

        assertThatThrownBy(() -> new FactConsistencyValidator().validate(
                ANALYSIS_ID,
                missingRuleSnapshot,
                validResult(202, 303, "CNY", "AMOUNT_THRESHOLD_EXCEEDED",
                        new BigDecimal("120000"), "UNSPECIFIED", List.of("reviewTask.ruleCode"))))
                .isInstanceOf(CopilotException.class)
                .extracting(exception -> ((CopilotException) exception).code())
                .isEqualTo(ErrorCode.FACT_VALIDATION_FAILED);
    }

    private void assertFails(AnalysisResult result) {
        assertThatThrownBy(() -> new FactConsistencyValidator().validate(ANALYSIS_ID, snapshot, result))
                .isInstanceOf(CopilotException.class)
                .extracting(exception -> ((CopilotException) exception).code())
                .isEqualTo(ErrorCode.FACT_VALIDATION_FAILED);
    }

    private static AnalysisResult validResult(AnalysisRecommendation recommendation) {
        return validResult(recommendation, new BigDecimal("120000"), "HIGH_AMOUNT",
                List.of("transaction.id", "transaction.accountId", "transaction.amount",
                        "transaction.currency", "reviewTask.ruleCode", "reviewTask.reasonCode"));
    }

    private static AnalysisResult validResult(
            AnalysisRecommendation recommendation,
            BigDecimal amount,
            String ruleCode,
            List<String> sourcePaths
    ) {
        return validResult(202, 303, "CNY", "AMOUNT_THRESHOLD_EXCEEDED",
                amount, ruleCode, sourcePaths, recommendation);
    }

    private static AnalysisResult validResult(
            long transactionId,
            long accountId,
            String currency,
            String reasonCode,
            BigDecimal amount,
            String ruleCode,
            List<String> sourcePaths
    ) {
        return validResult(transactionId, accountId, currency, reasonCode, amount, ruleCode,
                sourcePaths, AnalysisRecommendation.INSUFFICIENT_EVIDENCE);
    }

    private static AnalysisResult validResult(
            long transactionId,
            long accountId,
            String currency,
            String reasonCode,
            BigDecimal amount,
            String ruleCode,
            List<String> sourcePaths,
            AnalysisRecommendation recommendation
    ) {
        return new AnalysisResult(
                1,
                ANALYSIS_ID,
                "Review task 101 is PENDING.",
                "Rule " + ruleCode + " was recorded.",
                List.of(new AnalysisResult.BusinessEvidence(
                        transactionId, accountId, amount, currency, ruleCode, reasonCode, sourcePaths)),
                List.of(),
                recommendation,
                "The result is based on copied snapshot facts.",
                List.of("Published policy citation"),
                List.of("Phase 1 has no published knowledge index"));
    }
}
