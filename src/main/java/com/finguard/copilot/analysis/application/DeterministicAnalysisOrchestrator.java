package com.finguard.copilot.analysis.application;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.finguard.copilot.analysis.domain.AnalysisRecommendation;
import com.finguard.copilot.analysis.domain.AnalysisResult;
import com.finguard.copilot.analysis.domain.AnalysisResult.BusinessEvidence;
import com.finguard.copilot.common.error.CopilotException;
import com.finguard.copilot.common.error.ErrorCode;
import com.finguard.copilot.context.FactSnapshot;
import com.finguard.copilot.coreclient.port.ReviewTaskContext;
import com.finguard.copilot.guardrail.AnalysisOutputSchemaValidator;
import com.finguard.copilot.guardrail.FactConsistencyValidator;

public final class DeterministicAnalysisOrchestrator implements AnalysisOrchestrator {

    private static final String POLICY_CITATION_MISSING = "Published policy citation";
    private static final String NO_KNOWLEDGE_INDEX = "Phase 1 has no published knowledge index";

    private final AnalysisOutputSchemaValidator schemaValidator;
    private final FactConsistencyValidator factConsistencyValidator;

    public DeterministicAnalysisOrchestrator() {
        ObjectMapper mapper = new ObjectMapper();
        this.schemaValidator = new AnalysisOutputSchemaValidator(mapper);
        this.factConsistencyValidator = new FactConsistencyValidator();
    }

    public DeterministicAnalysisOrchestrator(
            AnalysisOutputSchemaValidator schemaValidator,
            FactConsistencyValidator factConsistencyValidator
    ) {
        this.schemaValidator = Objects.requireNonNull(schemaValidator, "schemaValidator must not be null");
        this.factConsistencyValidator = Objects.requireNonNull(
                factConsistencyValidator, "factConsistencyValidator must not be null");
    }

    @Override
    public AnalysisResult analyze(UUID analysisId, FactSnapshot snapshot) {
        Objects.requireNonNull(analysisId, "analysisId must not be null");
        Objects.requireNonNull(snapshot, "snapshot must not be null");

        ReviewTaskContext facts = snapshot.facts();
        ReviewTaskContext.ReviewTask reviewTask = facts.reviewTask();
        ReviewTaskContext.Transaction transaction = facts.transaction();
        String ruleCode = reviewTask.ruleCode();
        String reasonCode = reviewTask.reasonCode();
        if (ruleCode == null) {
            throw new CopilotException(
                    ErrorCode.FACT_VALIDATION_FAILED,
                    "snapshot ruleCode is unavailable for deterministic evidence");
        }

        AnalysisResult candidate = new AnalysisResult(
                AnalysisResult.CURRENT_SCHEMA_VERSION,
                analysisId,
                "Review task " + reviewTask.id() + " is " + reviewTask.status() + ".",
                "Rule " + ruleCode + " was recorded for reason " + reasonCode + ".",
                List.of(new BusinessEvidence(
                        transaction.id(),
                        transaction.accountId(),
                        transaction.amount(),
                        transaction.currency(),
                        ruleCode,
                        reasonCode,
                        List.of(
                                "transaction.id",
                                "transaction.accountId",
                                "transaction.amount",
                                "transaction.currency",
                                "reviewTask.ruleCode",
                                "reviewTask.reasonCode"))),
                List.of(),
                AnalysisRecommendation.INSUFFICIENT_EVIDENCE,
                "Directional recommendation is withheld because no published policy citation is available.",
                List.of(POLICY_CITATION_MISSING),
                List.of(NO_KNOWLEDGE_INDEX));

        AnalysisResult validated = schemaValidator.validate(candidate);
        factConsistencyValidator.validate(analysisId, snapshot, validated);
        return validated;
    }
}
