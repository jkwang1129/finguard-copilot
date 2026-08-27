package com.finguard.copilot.knowledge.qdrant;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finguard.copilot.knowledge.port.EmbeddingVector;
import com.finguard.copilot.knowledge.port.VectorDocument;
import com.finguard.copilot.knowledge.port.VectorSearchQuery;
import com.finguard.copilot.knowledge.port.VectorSearchResult;

public final class QdrantHttpClient implements QdrantClient {

    private static final List<String> METADATA_FIELDS = List.of(
            "documentId", "documentVersion", "chunkId", "sectionPath", "contentChecksum");

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final URI endpoint;
    private final Duration readTimeout;

    public QdrantHttpClient(QdrantConfiguration configuration, ObjectMapper objectMapper) {
        if (configuration == null) {
            throw new IllegalArgumentException("configuration must not be null");
        }
        if (objectMapper == null) {
            throw new IllegalArgumentException("objectMapper must not be null");
        }
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(configuration.connectTimeout())
                .build();
        this.objectMapper = objectMapper;
        this.endpoint = trimTrailingSlash(configuration.endpoint());
        this.readTimeout = configuration.readTimeout();
    }

    @Override
    public void createCollection(String collectionName, int dimension) {
        if (dimension < 1) {
            throw new IllegalArgumentException("dimension must be positive");
        }
        send("PUT", "/collections/" + pathSegment(collectionName), Map.of(
                "vectors", Map.of("size", dimension, "distance", "Cosine")));
    }

    @Override
    public void upsert(String collectionName, List<VectorDocument> documents) {
        if (documents == null || documents.stream().anyMatch(document -> document == null)) {
            throw new IllegalArgumentException("documents must not contain null");
        }
        List<Map<String, Object>> points = new ArrayList<>();
        for (VectorDocument document : documents) {
            Map<String, Object> payload = new LinkedHashMap<>();
            for (String field : METADATA_FIELDS) {
                if (document.metadata().containsKey(field)) {
                    payload.put(field, document.metadata().get(field));
                }
            }
            points.add(Map.of(
                    "id", UUID.nameUUIDFromBytes(document.id().getBytes(StandardCharsets.UTF_8)).toString(),
                    "vector", document.embedding().values(),
                    "payload", payload));
        }
        send("PUT", "/collections/" + pathSegment(collectionName) + "/points?wait=true",
                Map.of("points", points));
    }

    @Override
    public List<VectorSearchResult> search(String collectionName, VectorSearchQuery query) {
        String response = send("POST", "/collections/" + pathSegment(collectionName) + "/points/query", Map.of(
                "query", query.embedding().values(),
                "limit", query.topK(),
                "with_payload", true,
                "with_vector", true));
        try {
            JsonNode results = objectMapper.readTree(response).path("result").path("points");
            if (!results.isArray()) {
                throw malformedResponse("search result is not an array");
            }
            List<VectorSearchResult> mapped = new ArrayList<>();
            for (JsonNode result : results) {
                JsonNode payload = result.path("payload");
                String id = text(payload, "chunkId");
                if (id == null || !result.has("score")) {
                    throw malformedResponse("search result is missing required payload");
                }
                Map<String, String> metadata = new LinkedHashMap<>();
                for (String field : METADATA_FIELDS) {
                    String value = text(payload, field);
                    if (value != null) {
                        metadata.put(field, value);
                    }
                }
                mapped.add(new VectorSearchResult(
                        new VectorDocument(id, query.indexVersion(), "",
                                embedding(result.path("vector"), query.embedding()), metadata),
                        result.path("score").asDouble()));
            }
            return List.copyOf(mapped);
        } catch (JsonProcessingException | IllegalArgumentException exception) {
            if (exception instanceof QdrantException qdrantException) {
                throw qdrantException;
            }
            throw new QdrantException(QdrantException.KNOWLEDGE_UNAVAILABLE + ": malformed search response", exception);
        }
    }

    @Override
    public void deleteCollection(String collectionName) {
        send("DELETE", "/collections/" + pathSegment(collectionName), Map.of());
    }

    private String send(String method, String path, Object body) {
        try {
            HttpRequest request = HttpRequest.newBuilder(endpoint.resolve(path))
                    .timeout(readTimeout)
                    .header("Content-Type", "application/json")
                    .method(method, HttpRequest.BodyPublishers.ofString(toJson(body)))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new QdrantException(QdrantException.KNOWLEDGE_UNAVAILABLE
                        + ": Qdrant returned HTTP " + response.statusCode());
            }
            return response.body();
        } catch (QdrantException exception) {
            throw exception;
        } catch (IOException | InterruptedException | IllegalArgumentException exception) {
            if (exception instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new QdrantException(QdrantException.KNOWLEDGE_UNAVAILABLE + ": Qdrant request failed", exception);
        }
    }

    private String toJson(Object body) {
        try {
            return objectMapper.writeValueAsString(body);
        } catch (JsonProcessingException exception) {
            throw new QdrantException(QdrantException.KNOWLEDGE_UNAVAILABLE + ": request cannot be serialized", exception);
        }
    }

    private static EmbeddingVector embedding(JsonNode vector, EmbeddingVector fallback) {
        if (!vector.isArray()) {
            return fallback;
        }
        List<Double> values = new ArrayList<>();
        for (JsonNode value : vector) {
            values.add(value.asDouble());
        }
        return new EmbeddingVector(values);
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() || !value.isTextual() ? null : value.asText();
    }

    private static QdrantException malformedResponse(String message) {
        return new QdrantException(QdrantException.KNOWLEDGE_UNAVAILABLE + ": " + message);
    }

    private static URI trimTrailingSlash(URI value) {
        String text = value.toString();
        while (text.endsWith("/")) {
            text = text.substring(0, text.length() - 1);
        }
        return URI.create(text + "/");
    }

    private static String pathSegment(String value) {
        if (value == null || value.isBlank() || !value.matches("[A-Za-z0-9_-]+")) {
            throw new IllegalArgumentException("collectionName contains unsupported characters");
        }
        return value;
    }
}
