package com.finguard.copilot.guardrail;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import com.finguard.copilot.analysis.domain.AnalysisRecommendation;
import com.finguard.copilot.common.error.CopilotException;
import com.finguard.copilot.common.error.ErrorCode;
import com.finguard.copilot.coreclient.port.ReviewTaskContext;
import com.finguard.copilot.coreclient.port.ReviewTaskContext.RiskFact;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CoreLargeAmountFactGuardTest {
    private static final Instant NOW = Instant.parse("2026-10-27T00:00:00Z");
    private final CoreLargeAmountFactGuard guard = new CoreLargeAmountFactGuard();

    @ParameterizedTest
    @EnumSource(value = AnalysisRecommendation.class, names = {"CONFIRMED", "IGNORED"})
    void acceptsEqualBoundaryWithoutDictatingReviewDecision(AnalysisRecommendation recommendation) {
        assertThatCode(() -> guard.validate(context("LARGE_AMOUNT", "25000.00", facts("25000.00", "25000.00")),
                recommendation)).doesNotThrowAnyException();
        assertThatCode(() -> guard.validate(context("LARGE_AMOUNT", "25000.01", facts("25000.01", "25000.00")),
                recommendation)).doesNotThrowAnyException();
        assertThatCode(() -> guard.validate(context("LARGE_AMOUNT", "0.01", facts("0.01", "0.01")),
                recommendation)).doesNotThrowAnyException();
        assertThatCode(() -> guard.validate(context("LARGE_AMOUNT", "99999999999999999.99",
                facts("99999999999999999.99", "99999999999999999.99")), recommendation)).doesNotThrowAnyException();
    }

    @Test
    void rejectsMissingDuplicateForgedAndInconsistentFacts() {
        reject("25000.00", List.of());
        reject("25000.00", List.of(new RiskFact("OBSERVED_AMOUNT", "25000.00", "riskHit.observedAmount")));
        reject("25000.00", List.of(new RiskFact("OBSERVED_AMOUNT", "25000.00", "riskHit.observedAmount"),
                new RiskFact("THRESHOLD_AMOUNT", "10000.00", "riskHit.thresholdAmount"),
                new RiskFact("THRESHOLD_AMOUNT", "1.00", "reviewerInput")));
        reject("25000.00", List.of(new RiskFact("OBSERVED_AMOUNT", "25000.00", "riskHit.observedAmount"),
                new RiskFact("THRESHOLD_AMOUNT", "1.00", "reviewerInput")));
        reject("25000.00", facts("24999.99", "10000.00"));
        reject("9999.99", facts("9999.99", "10000.00"));
        reject("25000.00", facts("25000.00", "0.00"));
        reject("25000.00", facts("25000.00", "1e3"));
        reject("25000.00", facts("25000.00", "10000.001"));
        reject("25000.00", facts("25000.00", "999999999999999999.00"));
    }

    @Test
    void leavesOtherRulesAndConservativeRecommendationsAlone() {
        assertThatCode(() -> guard.validate(context("OTHER", "1.00", List.of()),
                AnalysisRecommendation.CONFIRMED)).doesNotThrowAnyException();
        assertThatCode(() -> guard.validate(context("LARGE_AMOUNT", "1.00", List.of()),
                AnalysisRecommendation.INSUFFICIENT_EVIDENCE)).doesNotThrowAnyException();
    }

    private void reject(String amount, List<RiskFact> facts) {
        for (AnalysisRecommendation recommendation : List.of(AnalysisRecommendation.CONFIRMED, AnalysisRecommendation.IGNORED)) {
            assertThatThrownBy(() -> guard.validate(context("LARGE_AMOUNT", amount, facts), recommendation))
                    .isInstanceOf(CopilotException.class)
                    .extracting(exception -> ((CopilotException) exception).code())
                    .isEqualTo(ErrorCode.FACT_VALIDATION_FAILED);
        }
    }

    private List<RiskFact> facts(String observed, String threshold) {
        return List.of(new RiskFact("OBSERVED_AMOUNT", observed, "riskHit.observedAmount"),
                new RiskFact("THRESHOLD_AMOUNT", threshold, "riskHit.thresholdAmount"));
    }

    private ReviewTaskContext context(String code, String amount, List<RiskFact> facts) {
        return new ReviewTaskContext(new ReviewTaskContext.ReviewTask(1, 0, "PENDING", code, "AMOUNT_AT_OR_ABOVE_THRESHOLD", NOW),
                new ReviewTaskContext.Transaction(2, 3, new BigDecimal(amount), "AUD", NOW, "CSV_IMPORT"),
                new ReviewTaskContext.Reconciliation("MATCHED", "REFERENCE_ID", "REASON"), facts,
                new ReviewTaskContext.AccountSummary("ACTIVE", "masked"), NOW);
    }
}
