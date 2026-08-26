package com.finguard.copilot.guardrail;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import com.finguard.copilot.analysis.domain.AnalysisRecommendation;
import com.finguard.copilot.analysis.domain.AnalysisResult;
import com.finguard.copilot.context.FactSnapshot;
import com.finguard.copilot.coreclient.port.ReviewTaskContext;
import com.finguard.copilot.common.error.CopilotException;
import com.finguard.copilot.common.error.ErrorCode;

public final class FactConsistencyValidator {

    public static final Set<String> ALLOWED_SOURCE_PATHS = Set.of(
            "transaction.id",
            "transaction.accountId",
            "transaction.amount",
            "transaction.currency",
            "reviewTask.ruleCode",
            "reviewTask.reasonCode");

    public void validate(FactSnapshot snapshot, AnalysisResult result) {
        Objects.requireNonNull(result, "result must not be null");
        validate(result.analysisId(), snapshot, result);
    }

    public void validate(UUID analysisId, FactSnapshot snapshot, AnalysisResult result) {
        if (analysisId == null || snapshot == null || result == null) {
            throw failed("analysis facts must not be null");
        }
        if (!analysisId.equals(result.analysisId())) {
            throw failed("analysisId does not match the requested analysis");
        }

        ReviewTaskContext facts = snapshot.facts();
        ReviewTaskContext.Transaction transaction = facts.transaction();
        ReviewTaskContext.ReviewTask reviewTask = facts.reviewTask();
        if (reviewTask.ruleCode() == null) {
            throw failed("snapshot ruleCode is unavailable for fact validation");
        }
        for (AnalysisResult.BusinessEvidence evidence : result.businessEvidence()) {
            if (evidence.transactionId() != transaction.id()
                    || evidence.accountId() != transaction.accountId()
                    || evidence.amount().compareTo(transaction.amount()) != 0
                    || !evidence.currency().equals(transaction.currency())
                    || !evidence.ruleCode().equals(reviewTask.ruleCode())
                    || !evidence.reasonCode().equals(reviewTask.reasonCode())) {
                throw failed("business evidence does not match the immutable fact snapshot");
            }
            if (!ALLOWED_SOURCE_PATHS.containsAll(evidence.sourcePaths())) {
                throw failed("business evidence contains an unknown fact source path");
            }
        }

        if ((result.recommendation() == AnalysisRecommendation.CONFIRMED
                || result.recommendation() == AnalysisRecommendation.IGNORED)
                && result.policyCitations().isEmpty()) {
            throw failed("directional recommendations require a policy citation");
        }
    }

    private static CopilotException failed(String message) {
        return new CopilotException(ErrorCode.FACT_VALIDATION_FAILED, message);
    }
}
