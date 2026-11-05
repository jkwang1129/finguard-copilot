package com.finguard.copilot.knowledge.index;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.finguard.copilot.knowledge.persistence.KnowledgeIndexRepository;
import com.finguard.copilot.knowledge.port.EmbeddingGateway;
import com.finguard.copilot.knowledge.port.VectorSearchQuery;
import com.finguard.copilot.knowledge.port.VectorSearchResult;
import com.finguard.copilot.knowledge.port.VectorStore;

public final class KnowledgeIndexEvaluationService {

    private final EmbeddingGateway embeddingGateway;
    private final VectorStore vectorStore;
    private final KnowledgeIndexRepository repository;
    private final String embeddingVersion;
    private final String evaluatorVersion;
    private final int topK;
    private final double minimumScore;

    public KnowledgeIndexEvaluationService(EmbeddingGateway embeddingGateway, VectorStore vectorStore,
                                           KnowledgeIndexRepository repository, String embeddingVersion,
                                           String evaluatorVersion, int topK, double minimumScore) {
        if (embeddingGateway == null || vectorStore == null || repository == null) {
            throw new IllegalArgumentException("evaluation dependencies must not be null");
        }
        if (embeddingVersion == null || embeddingVersion.isBlank()
                || evaluatorVersion == null || evaluatorVersion.isBlank()) {
            throw new IllegalArgumentException("evaluation versions must not be blank");
        }
        if (topK < 1 || !Double.isFinite(minimumScore) || minimumScore < -1.0 || minimumScore > 1.0) {
            throw new IllegalArgumentException("evaluation search parameters are invalid");
        }
        this.embeddingGateway = embeddingGateway;
        this.vectorStore = vectorStore;
        this.repository = repository;
        this.embeddingVersion = embeddingVersion;
        this.evaluatorVersion = evaluatorVersion;
        this.topK = topK;
        this.minimumScore = minimumScore;
    }

    public RagEvaluationResult evaluate(String indexVersion, List<RagCase> cases) {
        if (indexVersion == null || indexVersion.isBlank()) {
            throw new IllegalArgumentException("indexVersion must not be blank");
        }
        if (cases == null || cases.isEmpty() || cases.stream().anyMatch(value -> value == null)) {
            throw new IllegalArgumentException("cases must contain at least one non-null case");
        }
        double recall = 0.0;
        double citation = 0.0;
        double noAnswer = 0.0;
        int noAnswerCases = 0;
        try {
            for (RagCase ragCase : cases) {
                List<VectorSearchResult> results = vectorStore.search(new VectorSearchQuery(
                        indexVersion, embeddingGateway.embedQuery(ragCase.query(), embeddingVersion), topK));
                List<VectorSearchResult> accepted = results.stream()
                        .filter(result -> result.score() >= minimumScore)
                        .toList();
                Set<String> retrieved = accepted.stream().map(result -> result.document().id())
                        .collect(java.util.stream.Collectors.toCollection(java.util.LinkedHashSet::new));
                Set<String> expected = new HashSet<>(ragCase.expectedChunkIds());
                boolean forbiddenHit = accepted.stream()
                        .anyMatch(result -> ragCase.forbiddenChunkIds().contains(result.document().id()));
                double caseRecall = expected.isEmpty()
                        ? (retrieved.isEmpty() ? 1.0 : 0.0)
                        : expected.stream().filter(retrieved::contains).count() / (double) expected.size();
                recall += forbiddenHit ? 0.0 : caseRecall;
                citation += citationScore(ragCase, accepted);
                if (ragCase.expectedChunkIds().isEmpty()) {
                    noAnswerCases++;
                    noAnswer += retrieved.isEmpty() && !forbiddenHit ? 1.0 : 0.0;
                }
            }
            RagEvaluationResult evaluation = new RagEvaluationResult(indexVersion, recall / cases.size(),
                    citation / cases.size(), noAnswerCases == 0 ? 1.0 : noAnswer / noAnswerCases,
                    evaluatorVersion, cases.size());
            repository.markEvaluated(indexVersion, evaluation);
            return evaluation;
        } catch (RuntimeException exception) {
            repository.markFailed(indexVersion);
            throw exception;
        }
    }

    private double citationScore(RagCase ragCase, List<VectorSearchResult> results) {
        if (results.isEmpty()) {
            return ragCase.requiredCitationDocumentId() == null ? 1.0 : 0.0;
        }
        if (ragCase.requiredCitationDocumentId() == null) {
            return 1.0;
        }
        return results.stream().filter(result -> {
            var metadata = result.document().metadata();
            return ragCase.requiredCitationDocumentId().equals(metadata.get("documentId"))
                    && ragCase.requiredCitationDocumentVersion().equals(metadata.get("documentVersion"));
        }).count() / (double) results.size();
    }
}
