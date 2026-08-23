package com.finguard.copilot.foundation.stub;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.finguard.copilot.knowledge.port.EmbeddingVector;
import com.finguard.copilot.knowledge.port.VectorDocument;
import com.finguard.copilot.knowledge.port.VectorSearchQuery;
import com.finguard.copilot.knowledge.port.VectorSearchResult;
import com.finguard.copilot.knowledge.port.VectorStore;

public final class InMemoryVectorStore implements VectorStore {

    private static final Comparator<VectorSearchResult> RESULT_ORDER = Comparator
            .comparingDouble(VectorSearchResult::score)
            .reversed()
            .thenComparing(result -> result.document().id());

    private final Map<DocumentKey, VectorDocument> documents = new ConcurrentHashMap<>();

    @Override
    public void upsert(VectorDocument document) {
        if (document == null) {
            throw new IllegalArgumentException("document must not be null");
        }
        documents.put(new DocumentKey(document.indexVersion(), document.id()), document);
    }

    @Override
    public List<VectorSearchResult> search(VectorSearchQuery query) {
        if (query == null) {
            throw new IllegalArgumentException("query must not be null");
        }

        return documents.values().stream()
                .filter(document -> document.indexVersion().equals(query.indexVersion()))
                .map(document -> toResult(document, query.embedding()))
                .sorted(RESULT_ORDER)
                .limit(query.topK())
                .toList();
    }

    @Override
    public void clear() {
        documents.clear();
    }

    private VectorSearchResult toResult(VectorDocument document, EmbeddingVector queryEmbedding) {
        EmbeddingVector documentEmbedding = document.embedding();
        if (documentEmbedding.dimension() != queryEmbedding.dimension()) {
            throw new IllegalArgumentException("query and document vector dimensions must match");
        }
        return new VectorSearchResult(document, cosine(documentEmbedding, queryEmbedding));
    }

    private double cosine(EmbeddingVector first, EmbeddingVector second) {
        double dotProduct = 0.0;
        double firstSquaredNorm = 0.0;
        double secondSquaredNorm = 0.0;
        for (int index = 0; index < first.dimension(); index++) {
            double firstValue = first.values().get(index);
            double secondValue = second.values().get(index);
            dotProduct += firstValue * secondValue;
            firstSquaredNorm += firstValue * firstValue;
            secondSquaredNorm += secondValue * secondValue;
        }
        if (firstSquaredNorm == 0.0 || secondSquaredNorm == 0.0) {
            return 0.0;
        }
        double score = dotProduct / Math.sqrt(firstSquaredNorm * secondSquaredNorm);
        return Math.max(-1.0, Math.min(1.0, score));
    }

    private record DocumentKey(String indexVersion, String documentId) {
    }
}
