package com.finguard.copilot.knowledge.retrieval;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Map;

import com.finguard.copilot.knowledge.domain.KnowledgeCitation;
import com.finguard.copilot.knowledge.persistence.KnowledgeDocumentRepository;
import com.finguard.copilot.knowledge.persistence.KnowledgeIndexRepository;
import com.finguard.copilot.knowledge.port.VectorDocument;
import com.finguard.copilot.knowledge.port.EmbeddingGateway;
import com.finguard.copilot.knowledge.port.VectorSearchQuery;
import com.finguard.copilot.knowledge.port.VectorStore;

public final class KnowledgeRetrievalService {

    private final KnowledgeIndexRepository repository;
    private final EmbeddingGateway embeddingGateway;
    private final VectorStore vectorStore;
    private final String embeddingVersion;
    private final int maximumTopK;
    private final double minimumScore;
    private final KnowledgeDocumentRepository documentRepository;

    public KnowledgeRetrievalService(KnowledgeIndexRepository repository, EmbeddingGateway embeddingGateway,
                                     VectorStore vectorStore, String embeddingVersion,
                                     int maximumTopK, double minimumScore) {
        this(repository, embeddingGateway, vectorStore, embeddingVersion, maximumTopK, minimumScore, null);
    }

    public KnowledgeRetrievalService(KnowledgeIndexRepository repository, EmbeddingGateway embeddingGateway,
                                     VectorStore vectorStore, String embeddingVersion,
                                     int maximumTopK, double minimumScore,
                                     KnowledgeDocumentRepository documentRepository) {
        if (repository == null || embeddingGateway == null || vectorStore == null) {
            throw new IllegalArgumentException("retrieval dependencies must not be null");
        }
        if (embeddingVersion == null || embeddingVersion.isBlank() || maximumTopK < 1
                || !Double.isFinite(minimumScore) || minimumScore < -1 || minimumScore > 1) {
            throw new IllegalArgumentException("retrieval parameters are invalid");
        }
        this.repository = repository;
        this.embeddingGateway = embeddingGateway;
        this.vectorStore = vectorStore;
        this.embeddingVersion = embeddingVersion;
        this.maximumTopK = maximumTopK;
        this.minimumScore = minimumScore;
        this.documentRepository = documentRepository;
    }

    public List<KnowledgeEvidence> retrieve(String query, int topK) {
        return retrieveBatch(query,topK).evidence();
    }

    public record RetrievalBatch(String indexVersion,List<KnowledgeEvidence> evidence) {
        public RetrievalBatch { evidence=List.copyOf(evidence); }
    }

    public RetrievalBatch retrieveBatch(String query,int topK) {
        if (query == null || query.isBlank() || topK < 1 || topK > maximumTopK) {
            throw new IllegalArgumentException("retrieval query parameters are invalid");
        }
        String indexVersion = repository.findPublished()
                .map(value -> value.indexVersion())
                .orElseThrow(() -> new KnowledgeRetrievalException(
                        KnowledgeRetrievalException.KNOWLEDGE_UNAVAILABLE + ": no published index"));
        try {
            var evidence=vectorStore.search(new VectorSearchQuery(
                            indexVersion, embeddingGateway.embed(query, embeddingVersion), topK)).stream()
                    .filter(result -> result.score() >= minimumScore)
                    .map(result -> toEvidence(indexVersion, result.document(), result.score()))
                    .toList();
            return new RetrievalBatch(indexVersion,evidence);
        } catch (KnowledgeRetrievalException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new KnowledgeRetrievalException(
                    KnowledgeRetrievalException.KNOWLEDGE_UNAVAILABLE + ": vector search failed", exception);
        }
    }

    private KnowledgeEvidence toEvidence(String indexVersion, VectorDocument document, double score) {
        Map<String, String> metadata = document.metadata();
        String checksum = required(metadata, "contentChecksum");
        String content = document.content();
        if (content.isBlank()) {
            if (documentRepository == null) {
                throw new KnowledgeRetrievalException(
                        KnowledgeRetrievalException.KNOWLEDGE_UNAVAILABLE + ": vector result has no content source");
            }
            String documentId = required(metadata, "documentId");
            String documentVersion = required(metadata, "documentVersion");
            String chunkId = required(metadata, "chunkId");
            content = documentRepository.findChunks(documentId, documentVersion).stream()
                    .filter(chunk -> chunk.chunkId().equals(chunkId))
                    .map(chunk -> chunk.content())
                    .findFirst()
                    .orElseThrow(() -> new KnowledgeRetrievalException(
                            KnowledgeRetrievalException.KNOWLEDGE_UNAVAILABLE + ": vector chunk is not registered"));
        }
        if (!checksum.equals(sha256(content))) {
            throw new KnowledgeRetrievalException(
                    KnowledgeRetrievalException.KNOWLEDGE_UNAVAILABLE + ": content checksum mismatch");
        }
        return new KnowledgeEvidence(new KnowledgeCitation(
                required(metadata, "documentId"), required(metadata, "documentVersion"),
                required(metadata, "chunkId"), required(metadata, "sectionPath"), checksum, score, indexVersion), content);
    }

    private String required(Map<String, String> metadata, String key) {
        String value = metadata == null ? null : metadata.get(key);
        if (value == null || value.isBlank()) {
            throw new KnowledgeRetrievalException(
                    KnowledgeRetrievalException.KNOWLEDGE_UNAVAILABLE + ": missing vector metadata " + key);
        }
        return value;
    }

    private String sha256(String value) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
