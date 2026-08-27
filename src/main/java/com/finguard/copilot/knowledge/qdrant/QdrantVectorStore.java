package com.finguard.copilot.knowledge.qdrant;

import java.util.List;
import java.util.Set;

import com.finguard.copilot.knowledge.port.VectorDocument;
import com.finguard.copilot.knowledge.port.VectorSearchQuery;
import com.finguard.copilot.knowledge.port.VectorSearchResult;
import com.finguard.copilot.knowledge.port.VectorStore;

public final class QdrantVectorStore implements VectorStore {

    private static final Set<String> ALLOWED_METADATA = Set.of(
            "documentId", "documentVersion", "chunkId", "sectionPath", "contentChecksum");

    private final QdrantClient client;
    private final String collectionPrefix;

    public QdrantVectorStore(QdrantClient client, String collectionPrefix) {
        if (client == null) {
            throw new IllegalArgumentException("client must not be null");
        }
        if (collectionPrefix == null || collectionPrefix.isBlank()) {
            throw new IllegalArgumentException("collectionPrefix must not be blank");
        }
        this.client = client;
        this.collectionPrefix = collectionPrefix;
    }

    @Override
    public void createIndex(String indexVersion, int dimension) {
        client.createCollection(collectionName(indexVersion), dimension);
    }

    @Override
    public void upsert(VectorDocument document) {
        if (document == null) {
            throw new IllegalArgumentException("document must not be null");
        }
        upsert(document.indexVersion(), List.of(document));
    }

    @Override
    public void upsert(String indexVersion, List<VectorDocument> documents) {
        if (documents == null || documents.stream().anyMatch(document -> document == null)) {
            throw new IllegalArgumentException("documents must not contain null");
        }
        if (documents.stream().anyMatch(document -> !indexVersion.equals(document.indexVersion()))) {
            throw new IllegalArgumentException("document index version must match the target index");
        }
        if (documents.stream().anyMatch(document -> document.content().isBlank())) {
            throw new IllegalArgumentException("vector document content must not be blank when indexing");
        }
        if (documents.stream().anyMatch(document -> !ALLOWED_METADATA.containsAll(document.metadata().keySet()))) {
            throw new IllegalArgumentException("vector metadata contains a non-allowlisted field");
        }
        client.upsert(collectionName(indexVersion), List.copyOf(documents));
    }

    @Override
    public List<VectorSearchResult> search(VectorSearchQuery query) {
        if (query == null) {
            throw new IllegalArgumentException("query must not be null");
        }
        List<VectorSearchResult> results = client.search(collectionName(query.indexVersion()), query);
        if (results == null) {
            throw new QdrantException("Qdrant returned no result list");
        }
        return List.copyOf(results);
    }

    @Override
    public void deleteIndex(String indexVersion) {
        client.deleteCollection(collectionName(indexVersion));
    }

    @Override
    public void clear() {
        throw new UnsupportedOperationException("Qdrant requires an explicit index version for deletion");
    }

    private String collectionName(String indexVersion) {
        if (indexVersion == null || indexVersion.isBlank()
                || !indexVersion.matches("[A-Za-z0-9_-]+")) {
            throw new IllegalArgumentException("indexVersion contains unsupported characters");
        }
        return collectionPrefix + "-" + indexVersion;
    }
}
