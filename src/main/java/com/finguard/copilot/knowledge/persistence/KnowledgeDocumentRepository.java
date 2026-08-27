package com.finguard.copilot.knowledge.persistence;

import java.util.List;
import java.util.Optional;

import com.finguard.copilot.knowledge.domain.KnowledgeChunk;
import com.finguard.copilot.knowledge.domain.KnowledgeDocument;

public interface KnowledgeDocumentRepository {

    default Optional<KnowledgeDocument> findLatestByDocumentId(String documentId) {
        return Optional.empty();
    }

    default List<KnowledgeChunk> findChunks(String documentId, String documentVersion) {
        return List.of();
    }

    Optional<KnowledgeDocument> findByCanonicalChecksum(
            String documentId,
            String sourceVersion,
            String canonicalChecksum
    );

    void save(KnowledgeDocument document, List<KnowledgeChunk> chunks);
}
