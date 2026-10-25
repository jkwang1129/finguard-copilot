package com.finguard.copilot.context;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import com.finguard.copilot.common.error.CopilotException;
import com.finguard.copilot.common.error.ErrorCode;
import com.finguard.copilot.coreclient.port.ReviewTaskContext;
import com.finguard.copilot.knowledge.domain.KnowledgeCitation;
import com.finguard.copilot.knowledge.port.EmbeddingVector;
import com.finguard.copilot.knowledge.port.VectorSearchResult;
import com.finguard.copilot.knowledge.retrieval.KnowledgeEvidence;

public final class ContextAssembler {

    private static final String NO_KNOWLEDGE_VERSION = "none";
    private static final Comparator<VectorSearchResult> KNOWLEDGE_ORDER = Comparator
            .comparingDouble(VectorSearchResult::score).reversed()
            .thenComparing(result -> result.document().metadata().getOrDefault("documentId", result.document().id()));

    private final TokenBudget budget;
    private final TokenEstimator estimator;
    private final PromptTemplate promptTemplate;

    public ContextAssembler(TokenBudget budget, TokenEstimator estimator, PromptTemplate promptTemplate) {
        this.budget = Objects.requireNonNull(budget, "budget must not be null");
        this.estimator = Objects.requireNonNull(estimator, "estimator must not be null");
        this.promptTemplate = Objects.requireNonNull(promptTemplate, "promptTemplate must not be null");
    }

    public AssembledContext assemble(
            ReviewTaskContext facts,
            List<VectorSearchResult> results,
            String reviewerInput
    ) {
        Objects.requireNonNull(facts, "facts must not be null");
        if (results == null || results.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("knowledge results must not be null");
        }
        if (reviewerInput == null) {
            throw new CopilotException(ErrorCode.CONTEXT_BUDGET_EXCEEDED, "reviewer input must not be null");
        }

        String system = promptTemplate.render();
        ensureWithin(system, budget.systemTokens(), "system prompt");
        String factText = renderFacts(facts);
        ensureWithin(factText, budget.factsTokens(), "Core facts");
        ensureWithin(reviewerInput, budget.reviewerInputTokens(), "reviewer input");

        List<VectorSearchResult> ordered = results.stream().sorted(KNOWLEDGE_ORDER).toList();
        List<KnowledgePart> unique = deduplicate(ordered);
        List<KnowledgePart> retained = new ArrayList<>();
        List<String> trimmed = new ArrayList<>();
        int knowledgeTokens = 0;
        for (KnowledgePart part : unique) {
            int partTokens = estimate(part.text());
            if (partTokens <= budget.knowledgeTokens() - knowledgeTokens) {
                retained.add(part);
                knowledgeTokens += partTokens;
            } else {
                trimmed.add(part.documentId());
            }
        }
        String knowledgeText = renderKnowledge(retained);
        ensureWithin(knowledgeText, budget.knowledgeTokens(), "knowledge evidence");

        Map<String, String> sections = new LinkedHashMap<>();
        sections.put("system", system);
        sections.put("facts", factText);
        sections.put("knowledge", knowledgeText);
        sections.put("reviewerInput", reviewerInput);
        sections.put("reserve", "");

        long estimatedLong = (long) estimate(system) + estimate(factText) + estimate(knowledgeText)
                + estimate(reviewerInput) + budget.reserveTokens();
        if (estimatedLong > budget.maxInputTokens() || estimatedLong > Integer.MAX_VALUE) {
            throw new CopilotException(ErrorCode.CONTEXT_BUDGET_EXCEEDED,
                    "assembled context exceeds maximum input budget");
        }
        int estimated = (int) estimatedLong;
        String knowledgeVersion = unique.isEmpty()
                ? NO_KNOWLEDGE_VERSION : unique.get(0).indexVersion();
        for (KnowledgePart part : unique) {
            if (!knowledgeVersion.equals(part.indexVersion())) {
                throw new CopilotException(ErrorCode.INVALID_REQUEST,
                        "knowledge results must use one index version");
            }
        }
        return new AssembledContext(
                sections,
                estimated,
                retained.stream().map(KnowledgePart::documentId).toList(),
                List.copyOf(trimmed),
                promptTemplate.version(),
                factsVersion(facts),
                knowledgeVersion,
                retained.stream().map(KnowledgePart::citation).toList(),
                retained.stream().map(part -> new KnowledgeEvidence(part.citation(), part.text())).toList());
    }

    /**
     * Assemble evidence after published-index retrieval has verified its
     * source checksum. The placeholder vector is not used during assembly;
     * this method adapts the already validated evidence to the existing
     * metadata-driven rendering path.
     */
    public AssembledContext assembleRetrieved(
            ReviewTaskContext facts,
            List<KnowledgeEvidence> evidence,
            String reviewerInput
    ) {
        Objects.requireNonNull(evidence, "knowledge evidence must not be null");
        if (evidence.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("knowledge evidence must not contain null");
        }
        List<VectorSearchResult> results = evidence.stream()
                .map(item -> new VectorSearchResult(
                        new com.finguard.copilot.knowledge.port.VectorDocument(
                                item.citation().chunkId(),
                                item.citation().indexVersion(),
                                item.excerpt(),
                                new EmbeddingVector(List.of(1.0, 0.0)),
                                Map.of(
                                        "documentId", item.citation().documentId(),
                                        "documentVersion", item.citation().documentVersion(),
                                        "chunkId", item.citation().chunkId(),
                                        "sectionPath", item.citation().sectionPath(),
                                        "contentChecksum", item.citation().contentChecksum())),
                        item.citation().score()))
                .toList();
        return assemble(facts, results, reviewerInput);
    }

    private List<KnowledgePart> deduplicate(List<VectorSearchResult> ordered) {
        Set<String> checksums = new LinkedHashSet<>();
        Set<String> normalizedTexts = new LinkedHashSet<>();
        List<KnowledgePart> unique = new ArrayList<>();
        for (VectorSearchResult result : ordered) {
            Map<String, String> metadata = result.document().metadata();
            String checksum = required(metadata, "contentChecksum");
            String normalizedText = normalize(result.document().content());
            if (!checksums.add(checksum) || !normalizedTexts.add(normalizedText)) {
                continue;
            }
            unique.add(new KnowledgePart(
                    required(metadata, "documentId"),
                    required(metadata, "documentVersion"),
                    required(metadata, "chunkId"),
                    required(metadata, "sectionPath"),
                    checksum,
                    metadata.getOrDefault("source", metadata.getOrDefault("sourcePath", "knowledge-index")),
                    result.document().content(),
                    result.score(),
                    result.document().indexVersion()));
        }
        return unique;
    }

    private String renderFacts(ReviewTaskContext facts) {
        ReviewTaskContext.ReviewTask task = facts.reviewTask();
        ReviewTaskContext.Transaction transaction = facts.transaction();
        ReviewTaskContext.Reconciliation reconciliation = facts.reconciliation();
        StringBuilder text = new StringBuilder();
        append(text, "reviewTask.id", task.id());
        append(text, "reviewTask.version", task.version());
        append(text, "reviewTask.status", task.status());
        append(text, "reviewTask.ruleCode", task.ruleCode());
        append(text, "reviewTask.reasonCode", task.reasonCode());
        append(text, "transaction.id", transaction.id());
        append(text, "transaction.accountId", transaction.accountId());
        append(text, "transaction.amount", transaction.amount());
        append(text, "transaction.currency", transaction.currency());
        append(text, "transaction.occurredAt", transaction.occurredAt());
        append(text, "transaction.sourceType", transaction.sourceType());
        append(text, "reconciliation.resultType", reconciliation.resultType());
        append(text, "reconciliation.matchMethod", reconciliation.matchMethod());
        append(text, "reconciliation.reasonCode", reconciliation.reasonCode());
        append(text, "account.status", facts.accountSummary().status());
        append(text, "account.displayName", facts.accountSummary().displayName());
        append(text, "facts.capturedAt", facts.capturedAt());
        for (int index = 0; index < facts.riskFacts().size(); index++) {
            ReviewTaskContext.RiskFact fact = facts.riskFacts().get(index);
            append(text, "riskFacts[" + index + "].code", fact.code());
            append(text, "riskFacts[" + index + "].value", fact.value());
            append(text, "riskFacts[" + index + "].source", fact.source());
        }
        return text.toString();
    }

    private String renderKnowledge(List<KnowledgePart> parts) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(parts.stream()
                    .map(part -> Map.of(
                            "citation", new com.finguard.copilot.analysis.domain.AnalysisResult.PolicyCitation(
                                    part.documentId(), part.documentVersion(), part.sectionPath(), part.text()),
                            "contentChecksum", part.checksum(), "score", part.score(),
                            "indexVersion", part.indexVersion()))
                    .toList());
        } catch (com.fasterxml.jackson.core.JsonProcessingException exception) {
            throw new IllegalStateException("knowledge evidence cannot be rendered", exception);
        }
    }

    private void ensureWithin(String text, int partition, String name) {
        if (estimate(text) > partition) {
            throw new CopilotException(ErrorCode.CONTEXT_BUDGET_EXCEEDED,
                    name + " exceeds its dedicated context budget");
        }
    }

    private int estimate(String text) {
        try {
            int estimate = estimator.estimate(text);
            if (estimate < 0) {
                throw new IllegalArgumentException("token estimate must not be negative");
            }
            return estimate;
        } catch (RuntimeException exception) {
            throw new CopilotException(ErrorCode.CONTEXT_BUDGET_EXCEEDED,
                    "context token estimation failed");
        }
    }

    private String factsVersion(ReviewTaskContext facts) {
        return "core-capture-" + facts.capturedAt();
    }

    private String required(Map<String, String> metadata, String key) {
        String value = metadata.get(key);
        if (value == null || value.isBlank()) {
            throw new CopilotException(ErrorCode.INVALID_REQUEST, "knowledge metadata is missing " + key);
        }
        return value;
    }

    private String normalize(String text) {
        return text == null ? "" : text.trim().replaceAll("\\s+", " ");
    }

    private void append(StringBuilder text, String name, Object value) {
        text.append(name).append('=').append(value == null ? "<null>" : value).append('\n');
    }

    private record KnowledgePart(
            String documentId,
            String documentVersion,
            String chunkId,
            String sectionPath,
            String checksum,
            String source,
            String text,
            double score,
            String indexVersion
    ) {
        private KnowledgeCitation citation() {
            return new KnowledgeCitation(documentId, documentVersion, chunkId,
                    sectionPath, checksum, score, indexVersion);
        }
    }

    public record AssembledContext(
            Map<String, String> sections,
            int estimatedInputTokens,
            List<String> retainedDocumentIds,
            List<String> trimmedDocumentIds,
            String promptVersion,
            String inputFactsVersion,
            String knowledgeIndexVersion,
            List<KnowledgeCitation> retainedCitations,
            List<KnowledgeEvidence> retainedEvidence
    ) {
        public AssembledContext {
            sections = Map.copyOf(sections);
            retainedDocumentIds = List.copyOf(retainedDocumentIds);
            trimmedDocumentIds = List.copyOf(trimmedDocumentIds);
            retainedCitations = List.copyOf(retainedCitations);
            retainedEvidence = List.copyOf(retainedEvidence);
        }
    }
}
