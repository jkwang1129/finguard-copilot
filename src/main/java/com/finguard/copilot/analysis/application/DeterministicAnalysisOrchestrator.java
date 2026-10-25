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
import com.finguard.copilot.knowledge.retrieval.KnowledgeCitationValidator;
import com.finguard.copilot.knowledge.retrieval.KnowledgeEvidence;
import com.finguard.copilot.knowledge.retrieval.KnowledgeRetrievalException;
import com.finguard.copilot.knowledge.retrieval.KnowledgeRetrievalService;

public final class DeterministicAnalysisOrchestrator implements AnalysisOrchestrator {

    private static final String POLICY_CITATION_MISSING = "Published policy citation";
    private static final String NO_KNOWLEDGE_INDEX = "Phase 1 has no published knowledge index";

    private final AnalysisOutputSchemaValidator schemaValidator;
    private final FactConsistencyValidator factConsistencyValidator;
    private final KnowledgeRetrievalService retrievalService;
    private final KnowledgeCitationValidator citationValidator;

    public DeterministicAnalysisOrchestrator() {
        ObjectMapper mapper = new ObjectMapper();
        this.schemaValidator = new AnalysisOutputSchemaValidator(mapper);
        this.factConsistencyValidator = new FactConsistencyValidator();
        this.retrievalService = null;
        this.citationValidator = new KnowledgeCitationValidator();
    }

    public DeterministicAnalysisOrchestrator(
            AnalysisOutputSchemaValidator schemaValidator,
            FactConsistencyValidator factConsistencyValidator
    ) {
        this(schemaValidator, factConsistencyValidator, null);
    }

    public DeterministicAnalysisOrchestrator(
            AnalysisOutputSchemaValidator schemaValidator,
            FactConsistencyValidator factConsistencyValidator,
            KnowledgeRetrievalService retrievalService
    ) {
        this.schemaValidator = Objects.requireNonNull(schemaValidator, "schemaValidator must not be null");
        this.factConsistencyValidator = Objects.requireNonNull(
                factConsistencyValidator, "factConsistencyValidator must not be null");
        this.retrievalService = retrievalService;
        this.citationValidator = new KnowledgeCitationValidator();
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

        List<KnowledgeEvidence> evidence = retrievePolicyEvidence(ruleCode + " " + reasonCode);
        List<com.finguard.copilot.analysis.domain.AnalysisResult.PolicyCitation> policyCitations =
                evidence.stream().map(item -> new com.finguard.copilot.analysis.domain.AnalysisResult.PolicyCitation(
                        item.citation().documentId(), item.citation().documentVersion(),
                        item.citation().sectionPath(), item.excerpt())).toList();
        citationValidator.validate(evidence, evidence.stream().map(KnowledgeEvidence::citation).toList());
        List<String> missingInformation = policyCitations.isEmpty() ? List.of(POLICY_CITATION_MISSING) : List.of();
        List<String> limitations = policyCitations.isEmpty() ? List.of(NO_KNOWLEDGE_INDEX) : List.of();

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
                policyCitations,
                AnalysisRecommendation.INSUFFICIENT_EVIDENCE,
                "Directional recommendation is withheld because no published policy citation is available.",
                missingInformation,
                limitations);

        AnalysisResult validated = schemaValidator.validate(candidate);
        factConsistencyValidator.validate(analysisId, snapshot, validated);
        return validated;
    }

    private List<KnowledgeEvidence> retrievePolicyEvidence(String query) {
        if (retrievalService == null) {
            return List.of();
        }
        try {
            return retrievalService.retrieve(query, 3);
        } catch (KnowledgeRetrievalException exception) {
            return List.of();
        }
    }
}
