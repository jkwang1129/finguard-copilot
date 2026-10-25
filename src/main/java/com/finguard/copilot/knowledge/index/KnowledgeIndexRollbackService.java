package com.finguard.copilot.knowledge.index;

import com.finguard.copilot.knowledge.domain.KnowledgeIndexVersion;
import com.finguard.copilot.knowledge.persistence.KnowledgeIndexRepository;

public final class KnowledgeIndexRollbackService {

    private final KnowledgeIndexRepository repository;

    public KnowledgeIndexRollbackService(KnowledgeIndexRepository repository) {
        if (repository == null) {
            throw new IllegalArgumentException("repository must not be null");
        }
        this.repository = repository;
    }

    public KnowledgeIndexVersion rollback(String targetPublishedVersion, String currentPublishedVersion) {
        if (targetPublishedVersion == null || targetPublishedVersion.isBlank()
                || currentPublishedVersion == null || currentPublishedVersion.isBlank()) {
            throw new KnowledgeIndexPublicationException("rollback versions must not be blank");
        }
        if (targetPublishedVersion.equals(currentPublishedVersion)) {
            throw new KnowledgeIndexPublicationException("rollback target must differ from current version");
        }
        if (repository.find(targetPublishedVersion).isEmpty()
                || repository.findChunks(targetPublishedVersion).isEmpty()) {
            throw new KnowledgeIndexPublicationException("rollback target is not retained: " + targetPublishedVersion);
        }
        try {
            repository.restoreAndPublish(targetPublishedVersion, currentPublishedVersion);
        } catch (RuntimeException exception) {
            throw new KnowledgeIndexPublicationException("rollback could not be published", exception);
        }
        return repository.find(targetPublishedVersion)
                .orElseThrow(() -> new KnowledgeIndexPublicationException("rollback target disappeared"));
    }
}
