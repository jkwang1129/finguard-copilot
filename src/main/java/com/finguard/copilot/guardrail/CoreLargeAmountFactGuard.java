package com.finguard.copilot.guardrail;

import java.math.BigDecimal;
import java.util.List;

import com.finguard.copilot.analysis.domain.AnalysisRecommendation;
import com.finguard.copilot.common.error.CopilotException;
import com.finguard.copilot.common.error.ErrorCode;
import com.finguard.copilot.coreclient.port.ReviewTaskContext;
import com.finguard.copilot.coreclient.port.ReviewTaskContext.RiskFact;

/** Checks the persisted Core risk-hit predicate, not the review decision. */
public final class CoreLargeAmountFactGuard {
    public void validate(ReviewTaskContext context, AnalysisRecommendation recommendation) {
        if (!"LARGE_AMOUNT".equals(context.reviewTask().ruleCode())
                || recommendation == AnalysisRecommendation.INSUFFICIENT_EVIDENCE) {
            return;
        }
        BigDecimal observed = amount(context.riskFacts(), "OBSERVED_AMOUNT", "riskHit.observedAmount");
        BigDecimal threshold = amount(context.riskFacts(), "THRESHOLD_AMOUNT", "riskHit.thresholdAmount");
        if (threshold.compareTo(new BigDecimal("0.01")) < 0
                || observed.compareTo(context.transaction().amount()) != 0
                || observed.compareTo(threshold) < 0) {
            throw rejected();
        }
    }

    private BigDecimal amount(List<RiskFact> facts, String code, String source) {
        List<RiskFact> matches = facts.stream().filter(fact -> code.equals(fact.code())).toList();
        if (matches.size() != 1 || !source.equals(matches.get(0).source())
                || !matches.get(0).value().matches("[0-9]{1,17}(\\.[0-9]{1,2})?")) {
            throw rejected();
        }
        return new BigDecimal(matches.get(0).value());
    }

    private CopilotException rejected() {
        return new CopilotException(ErrorCode.FACT_VALIDATION_FAILED,
                "directional output could not be verified against Core large amount facts");
    }
}
