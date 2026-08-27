package com.finguard.copilot.knowledge.qdrant;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.finguard.copilot.knowledge.port.EmbeddingVector;
import com.finguard.copilot.knowledge.port.VectorDocument;
import com.finguard.copilot.knowledge.port.VectorSearchQuery;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
class QdrantVectorStoreIntegrationTest {

    @Container
    static final GenericContainer<?> QDRANT = new GenericContainer<>("qdrant/qdrant:v1.15.5")
            .withExposedPorts(6333)
            .waitingFor(Wait.forHttp("/healthz").forPort(6333).forStatusCode(200))
            .withStartupTimeout(Duration.ofMinutes(2));

    @Test
    void isolatesIndexVersionsAndDeletesTheExplicitCollection() {
        QdrantHttpClient client = new QdrantHttpClient(new QdrantConfiguration(
                URI.create("http://" + QDRANT.getHost() + ":" + QDRANT.getMappedPort(6333)),
                "integration-knowledge", Duration.ofSeconds(2), Duration.ofSeconds(10), 4),
                new ObjectMapper());
        QdrantVectorStore store = new QdrantVectorStore(client, "integration-knowledge");
        VectorDocument v1 = document("doc-1:v1:0", "v1", "old policy", 1.0, 0.0);
        VectorDocument v2 = document("doc-1:v2:0", "v2", "new policy", 1.0, 0.0);

        store.createIndex("v1", 4);
        store.createIndex("v2", 4);
        store.upsert("v1", List.of(v1));
        store.upsert("v2", List.of(v2));

        assertThat(store.search(new VectorSearchQuery("v1", vector(1.0, 0.0), 5)))
                .extracting(result -> result.document().metadata().get("chunkId"))
                .containsExactly("doc-1:v1:0");
        assertThat(store.search(new VectorSearchQuery("v2", vector(1.0, 0.0), 5)))
                .extracting(result -> result.document().metadata().get("chunkId"))
                .containsExactly("doc-1:v2:0");

        store.deleteIndex("v1");

        assertThatThrownBy(() -> store.search(new VectorSearchQuery("v1", vector(1.0, 0.0), 5)))
                .isInstanceOf(QdrantException.class)
                .hasMessageContaining(QdrantException.KNOWLEDGE_UNAVAILABLE);
        assertThat(store.search(new VectorSearchQuery("v2", vector(1.0, 0.0), 5))).hasSize(1);
    }

    private static VectorDocument document(
            String id, String indexVersion, String content, double first, double second) {
        return new VectorDocument(id, indexVersion, content, vector(first, second), Map.of(
                "documentId", "doc-1",
                "documentVersion", indexVersion,
                "chunkId", id,
                "sectionPath", "Risk",
                "contentChecksum", "checksum-" + indexVersion));
    }

    private static EmbeddingVector vector(double first, double second) {
        return new EmbeddingVector(List.of(first, second, 0.0, 0.0));
    }
}
