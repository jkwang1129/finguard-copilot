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
    private final Map<String, Integer> indexDimensions = new ConcurrentHashMap<>();

    @Override
    public void createIndex(String indexVersion, int dimension) {
        requireIndexVersion(indexVersion);
        if (dimension < 1) {
            throw new IllegalArgumentException("dimension must be positive");
        }
        Integer existing = indexDimensions.putIfAbsent(indexVersion, dimension);
        if (existing != null && existing != dimension) {
            throw new IllegalArgumentException("index dimension cannot change");
        }
    }

    @Override
    public void upsert(VectorDocument document) {
        if (document == null) {
            throw new IllegalArgumentException("document must not be null");
        }
        createIndex(document.indexVersion(), document.embedding().dimension());
        documents.put(new DocumentKey(document.indexVersion(), document.id()), document);
    }

    @Override
    public void upsert(String indexVersion, List<VectorDocument> documents) {
        requireIndexVersion(indexVersion);
        if (documents == null || documents.stream().anyMatch(document -> document == null)) {
            throw new IllegalArgumentException("documents must not contain null");
        }
        documents.forEach(document -> {
            if (!indexVersion.equals(document.indexVersion())) {
                throw new IllegalArgumentException("document index version must match the target index");
            }
            upsert(document);
        });
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
        indexDimensions.clear();
    }

    @Override
    public void deleteIndex(String indexVersion) {
        requireIndexVersion(indexVersion);
        documents.keySet().removeIf(key -> key.indexVersion().equals(indexVersion));
        indexDimensions.remove(indexVersion);
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

    private static void requireIndexVersion(String indexVersion) {
        if (indexVersion == null || indexVersion.isBlank()) {
            throw new IllegalArgumentException("indexVersion must not be blank");
        }
    }
}
