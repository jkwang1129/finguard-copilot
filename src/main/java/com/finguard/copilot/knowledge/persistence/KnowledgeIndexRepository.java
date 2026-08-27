package com.finguard.copilot.knowledge.persistence;

import java.util.Optional;
import java.util.List;

import com.finguard.copilot.knowledge.domain.KnowledgeChunk;
import com.finguard.copilot.knowledge.domain.KnowledgeIndexVersion;
import com.finguard.copilot.knowledge.index.RagEvaluationResult;

public interface KnowledgeIndexRepository {

    void save(KnowledgeIndexVersion version);

    default void save(KnowledgeIndexVersion version, List<KnowledgeChunk> chunks) {
        save(version);
    }

    default List<KnowledgeChunk> findChunks(String indexVersion) {
        return List.of();
    }

    Optional<KnowledgeIndexVersion> find(String indexVersion);

    Optional<KnowledgeIndexVersion> findPublished();

    void markEvaluated(String indexVersion, RagEvaluationResult result);

    void markFailed(String indexVersion);

    void compareAndPublish(String candidateVersion, String expectedCurrentVersion);

    default void restoreAndPublish(String targetVersion, String expectedCurrentVersion) {
        compareAndPublish(targetVersion, expectedCurrentVersion);
    }
}
