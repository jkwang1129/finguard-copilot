package com.finguard.copilot.knowledge.index;

import java.time.Instant;

import com.finguard.copilot.knowledge.domain.KnowledgeIndexStatus;
import com.finguard.copilot.knowledge.domain.KnowledgeIndexVersion;
import com.finguard.copilot.knowledge.persistence.KnowledgeIndexRepository;

public final class KnowledgeIndexPublicationService {

    private final KnowledgeIndexRepository repository;
    private final PublicationThresholds thresholds;

    public KnowledgeIndexPublicationService(KnowledgeIndexRepository repository, PublicationThresholds thresholds) {
        if (repository == null || thresholds == null) {
            throw new IllegalArgumentException("publication dependencies must not be null");
        }
        this.repository = repository;
        this.thresholds = thresholds;
    }

    public KnowledgeIndexVersion publish(String candidateVersion, RagEvaluationResult result) {
        if (candidateVersion == null || candidateVersion.isBlank() || result == null
                || !candidateVersion.equals(result.indexVersion())) {
            throw new KnowledgeIndexPublicationException("candidate and evaluation do not match");
        }
        if (!thresholds.accepts(result)) {
            throw new KnowledgeIndexPublicationException("candidate evaluation is below publication threshold");
        }
        KnowledgeIndexVersion current = repository.findPublished().orElse(null);
        String expected = current == null ? null : current.indexVersion();
        try {
            repository.compareAndPublish(candidateVersion, expected);
        } catch (RuntimeException exception) {
            throw new KnowledgeIndexPublicationException("stale publication request", exception);
        }
        return repository.find(candidateVersion).orElseGet(() -> new KnowledgeIndexVersion(
                candidateVersion, KnowledgeIndexStatus.PUBLISHED, Instant.now(), Instant.now(), expected));
    }
}
