package com.finguard.copilot.knowledge.index;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.finguard.copilot.knowledge.domain.KnowledgeIndexStatus;
import com.finguard.copilot.knowledge.domain.KnowledgeIndexVersion;
import com.finguard.copilot.knowledge.persistence.KnowledgeIndexRepository;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class KnowledgeIndexPublicationServiceTest {

    @Test
    void publishesCandidateOnlyWhenAllConfiguredMetricsMeetThresholds() {
        InMemoryRepository repository = new InMemoryRepository(
                new KnowledgeIndexVersion("old", KnowledgeIndexStatus.PUBLISHED,
                        Instant.parse("2026-01-01T00:00:00Z"), Instant.parse("2026-01-01T00:00:01Z"), null));
        KnowledgeIndexPublicationService service = new KnowledgeIndexPublicationService(
                repository, new PublicationThresholds(0.8, 0.8, 0.9));

        KnowledgeIndexVersion published = service.publish("candidate", new RagEvaluationResult(
                "candidate", 0.9, 0.85, 1.0, "rag-evaluator-v1", 2));

        assertThat(published.status()).isEqualTo(KnowledgeIndexStatus.PUBLISHED);
        assertThat(repository.expectedCurrentVersion).isEqualTo("old");
        assertThat(repository.candidateVersion).isEqualTo("candidate");
    }

    @Test
    void rejectsThresholdFailureWithoutChangingTheCurrentVersion() {
        InMemoryRepository repository = new InMemoryRepository(
                new KnowledgeIndexVersion("old", KnowledgeIndexStatus.PUBLISHED,
                        Instant.parse("2026-01-01T00:00:00Z"), Instant.parse("2026-01-01T00:00:01Z"), null));
        KnowledgeIndexPublicationService service = new KnowledgeIndexPublicationService(
                repository, new PublicationThresholds(0.8, 0.8, 0.9));

        assertThatThrownBy(() -> service.publish("candidate", new RagEvaluationResult(
                "candidate", 0.7, 0.85, 1.0, "rag-evaluator-v1", 2)))
                .isInstanceOf(KnowledgeIndexPublicationException.class)
                .hasMessageContaining("threshold");
        assertThat(repository.candidateVersion).isNull();
        assertThat(repository.findPublished()).get().extracting(KnowledgeIndexVersion::indexVersion)
                .isEqualTo("old");
    }

    private static final class InMemoryRepository implements KnowledgeIndexRepository {
        private KnowledgeIndexVersion published;
        private String expectedCurrentVersion;
        private String candidateVersion;

        private InMemoryRepository(KnowledgeIndexVersion published) {
            this.published = published;
        }

        @Override
        public void save(KnowledgeIndexVersion version) {
        }

        @Override
        public Optional<KnowledgeIndexVersion> find(String indexVersion) {
            if (published.indexVersion().equals(indexVersion)) {
                return Optional.of(published);
            }
            return Optional.of(new KnowledgeIndexVersion(indexVersion, KnowledgeIndexStatus.EVALUATING,
                    Instant.now(), null, published.indexVersion()));
        }

        @Override
        public Optional<KnowledgeIndexVersion> findPublished() {
            return Optional.of(published);
        }

        @Override
        public void markFailed(String indexVersion) {
        }

        @Override
        public void markEvaluated(String indexVersion, RagEvaluationResult result) {
        }

        @Override
        public void compareAndPublish(String candidateVersion, String expectedCurrentVersion) {
            this.candidateVersion = candidateVersion;
            this.expectedCurrentVersion = expectedCurrentVersion;
            this.published = new KnowledgeIndexVersion(candidateVersion, KnowledgeIndexStatus.PUBLISHED,
                    Instant.now(), Instant.now(), expectedCurrentVersion);
        }
    }
}
