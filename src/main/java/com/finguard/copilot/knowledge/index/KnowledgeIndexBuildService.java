package com.finguard.copilot.knowledge.index;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import com.finguard.copilot.knowledge.domain.KnowledgeChunk;
import com.finguard.copilot.knowledge.domain.KnowledgeIndexStatus;
import com.finguard.copilot.knowledge.domain.KnowledgeIndexVersion;
import com.finguard.copilot.knowledge.persistence.KnowledgeIndexRepository;
import com.finguard.copilot.knowledge.port.EmbeddingGateway;
import com.finguard.copilot.knowledge.port.EmbeddingVector;
import com.finguard.copilot.knowledge.port.VectorDocument;
import com.finguard.copilot.knowledge.port.VectorStore;

public final class KnowledgeIndexBuildService {

    private final EmbeddingGateway embeddingGateway;
    private final VectorStore vectorStore;
    private final KnowledgeIndexRepository repository;
    private final String embeddingVersion;
    private final Supplier<Instant> now;

    public KnowledgeIndexBuildService(EmbeddingGateway embeddingGateway, VectorStore vectorStore,
                                      KnowledgeIndexRepository repository, String embeddingVersion,
                                      Supplier<Instant> now) {
        if (embeddingGateway == null || vectorStore == null || repository == null || now == null) {
            throw new IllegalArgumentException("build dependencies must not be null");
        }
        if (embeddingVersion == null || embeddingVersion.isBlank()) {
            throw new IllegalArgumentException("embeddingVersion must not be blank");
        }
        this.embeddingGateway = embeddingGateway;
        this.vectorStore = vectorStore;
        this.repository = repository;
        this.embeddingVersion = embeddingVersion;
        this.now = now;
    }

    public KnowledgeIndexVersion build(String indexVersion, List<KnowledgeChunk> chunks) {
        if (indexVersion == null || indexVersion.isBlank()) {
            throw new IllegalArgumentException("indexVersion must not be blank");
        }
        if (chunks == null || chunks.isEmpty() || chunks.stream().anyMatch(chunk -> chunk == null)) {
            throw new IllegalArgumentException("chunks must contain at least one non-null chunk");
        }
        KnowledgeIndexVersion version = new KnowledgeIndexVersion(
                indexVersion, KnowledgeIndexStatus.EVALUATING, now.get(), null,
                repository.findPublished().map(KnowledgeIndexVersion::indexVersion).orElse(null));
        boolean indexCreated = false;
        try {
            repository.save(version, chunks);
            List<VectorDocument> documents = chunks.stream().map(chunk -> toVectorDocument(indexVersion, chunk)).toList();
            vectorStore.createIndex(indexVersion, documents.get(0).embedding().dimension());
            indexCreated = true;
            vectorStore.upsert(indexVersion, documents);
            return version;
        } catch (RuntimeException exception) {
            repository.markFailed(indexVersion);
            if (indexCreated) {
                try {
                    vectorStore.deleteIndex(indexVersion);
                } catch (RuntimeException ignored) {
                    // The candidate remains unreachable because it is never published.
                }
            }
            throw exception;
        }
    }

    private VectorDocument toVectorDocument(String indexVersion, KnowledgeChunk chunk) {
        EmbeddingVector embedding = embeddingGateway.embed(chunk.content(), embeddingVersion);
        return new VectorDocument(chunk.chunkId(), indexVersion, chunk.content(), embedding, Map.of(
                "documentId", chunk.documentId(),
                "documentVersion", chunk.documentVersion(),
                "chunkId", chunk.chunkId(),
                "sectionPath", chunk.sectionPath(),
                "contentChecksum", chunk.contentChecksum()));
    }
}
