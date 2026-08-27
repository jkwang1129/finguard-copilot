package com.finguard.copilot.knowledge.qdrant;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.finguard.copilot.knowledge.port.EmbeddingVector;
import com.finguard.copilot.knowledge.port.VectorDocument;
import com.finguard.copilot.knowledge.port.VectorSearchQuery;
import com.finguard.copilot.knowledge.port.VectorSearchResult;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class QdrantVectorStoreTest {

    @Test
    void mapsIndexVersionsToIsolatedCollectionsAndDelegatesOnlyAllowlistedPayloads() {
        RecordingClient client = new RecordingClient();
        QdrantVectorStore store = new QdrantVectorStore(client, "finguard-knowledge");
        VectorDocument document = new VectorDocument(
                "doc-1:v1:0", "v1", "policy text", new EmbeddingVector(List.of(1.0, 0.0)),
                Map.of("documentId", "doc-1", "documentVersion", "v1", "chunkId", "doc-1:v1:0"));

        store.createIndex("v1", 2);
        store.upsert("v1", List.of(document));
        List<VectorSearchResult> result = store.search(new VectorSearchQuery(
                "v1", new EmbeddingVector(List.of(1.0, 0.0)), 1));
        store.deleteIndex("v1");

        assertThat(client.collections).containsExactly("finguard-knowledge-v1");
        assertThat(client.upserted).containsExactly(document);
        assertThat(result).containsExactly(new VectorSearchResult(document, 1.0));
        assertThat(client.deleted).containsExactly("finguard-knowledge-v1");
    }

    @Test
    void rejectsDynamicOrOperationalFieldsFromVectorPayloads() {
        RecordingClient client = new RecordingClient();
        QdrantVectorStore store = new QdrantVectorStore(client, "finguard-knowledge");
        VectorDocument document = new VectorDocument(
                "doc-1:v1:0", "v1", "policy text", new EmbeddingVector(List.of(1.0, 0.0)),
                Map.of("documentId", "doc-1", "documentVersion", "v1", "chunkId", "doc-1:v1:0",
                        "amount", "120000"));

        assertThatThrownBy(() -> store.upsert("v1", List.of(document)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("allowlisted");
    }

    private static final class RecordingClient implements QdrantClient {
        private final List<String> collections = new ArrayList<>();
        private final List<VectorDocument> upserted = new ArrayList<>();
        private final List<String> deleted = new ArrayList<>();

        @Override
        public void createCollection(String collectionName, int dimension) {
            collections.add(collectionName);
        }

        @Override
        public void upsert(String collectionName, List<VectorDocument> documents) {
            upserted.addAll(documents);
        }

        @Override
        public List<VectorSearchResult> search(String collectionName, VectorSearchQuery query) {
            return upserted.stream().map(document -> new VectorSearchResult(document, 1.0)).toList();
        }

        @Override
        public void deleteCollection(String collectionName) {
            deleted.add(collectionName);
        }
    }
}
