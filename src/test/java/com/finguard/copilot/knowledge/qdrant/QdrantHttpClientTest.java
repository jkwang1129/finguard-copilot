package com.finguard.copilot.knowledge.qdrant;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.finguard.copilot.knowledge.port.EmbeddingVector;
import com.finguard.copilot.knowledge.port.VectorDocument;
import com.finguard.copilot.knowledge.port.VectorSearchQuery;
import com.finguard.copilot.knowledge.port.VectorSearchResult;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class QdrantHttpClientTest {

    private HttpServer server;
    private RecordingHandler handler;
    private QdrantHttpClient client;

    @BeforeEach
    void setUp() throws IOException {
        handler = new RecordingHandler();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", handler);
        server.start();
        client = new QdrantHttpClient(new QdrantConfiguration(
                URI.create("http://127.0.0.1:" + server.getAddress().getPort()),
                "knowledge", Duration.ofSeconds(1), Duration.ofSeconds(2), 2),
                new ObjectMapper());
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    void executesTheVersionedCollectionLifecycleAndParsesSearchPayload() {
        VectorDocument document = new VectorDocument(
                "doc-1:v1:0", "v1", "policy text", new EmbeddingVector(List.of(1.0, 0.0)),
                Map.of("documentId", "doc-1", "documentVersion", "v1", "chunkId", "doc-1:v1:0"));

        client.createCollection("knowledge-v1", 2);
        client.upsert("knowledge-v1", List.of(document));
        List<VectorSearchResult> results = client.search("knowledge-v1",
                new VectorSearchQuery("v1", new EmbeddingVector(List.of(1.0, 0.0)), 3));
        client.deleteCollection("knowledge-v1");

        assertThat(handler.methods).containsExactly("PUT", "PUT", "POST", "DELETE");
        assertThat(handler.paths).containsExactly(
                "/collections/knowledge-v1",
                "/collections/knowledge-v1/points?wait=true",
                "/collections/knowledge-v1/points/query",
                "/collections/knowledge-v1");
        assertThat(handler.bodies.get(0)).contains("\"size\":2", "\"distance\":\"Cosine\"");
        assertThat(handler.bodies.get(1)).contains("doc-1:v1:0").doesNotContain("policy text", "\"content\"");
        assertThat(handler.bodies.get(2)).contains("\"query\"", "\"limit\":3");
        assertThat(results).singleElement().satisfies(result -> {
            assertThat(result.score()).isEqualTo(0.95);
            assertThat(result.document().id()).isEqualTo("doc-1:v1:0");
            assertThat(result.document().content()).isEmpty();
        });
    }

    @Test
    void mapsNonSuccessfulResponsesToKnowledgeUnavailable() {
        handler.status = 503;

        assertThatThrownBy(() -> client.createCollection("knowledge-v1", 2))
                .isInstanceOf(QdrantException.class)
                .hasMessageContaining(QdrantException.KNOWLEDGE_UNAVAILABLE);
    }

    private static final class RecordingHandler implements com.sun.net.httpserver.HttpHandler {
        private final List<String> methods = new ArrayList<>();
        private final List<String> paths = new ArrayList<>();
        private final List<String> bodies = new ArrayList<>();
        private int status = 200;

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            methods.add(exchange.getRequestMethod());
            paths.add(exchange.getRequestURI().toString());
            bodies.add(new String(exchange.getRequestBody().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
            String body = exchange.getRequestURI().getPath().endsWith("/query")
                    ? """
                      {"result":{"points":[{"id":"doc-1-v1-0","version":1,"score":0.95,"payload":{"documentId":"doc-1","documentVersion":"v1","chunkId":"doc-1:v1:0","sectionPath":"Risk","contentChecksum":"checksum"},"vector":[1.0,0.0]}]},"status":"ok"}
                      """
                    : """
                      {"result":true,"status":"ok"}
                      """;
            byte[] response = body.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, response.length);
            try (var output = exchange.getResponseBody()) {
                output.write(response);
            }
        }
    }
}
