package com.finguard.copilot.knowledge.index;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import com.finguard.copilot.knowledge.domain.KnowledgeChunk;
import com.finguard.copilot.knowledge.domain.KnowledgeIndexStatus;
import com.finguard.copilot.knowledge.domain.KnowledgeIndexVersion;
import com.finguard.copilot.knowledge.persistence.KnowledgeIndexRepository;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class KnowledgeIndexRollbackServiceTest {

    @Test
    void restoresAretainedHistoricalVersionThroughTheRepositorySwap() {
        RecordingRepository repository = new RecordingRepository();
        KnowledgeIndexRollbackService service = new KnowledgeIndexRollbackService(repository);

        KnowledgeIndexVersion result = service.rollback("old", "current");

        assertThat(result.indexVersion()).isEqualTo("old");
        assertThat(result.status()).isEqualTo(KnowledgeIndexStatus.PUBLISHED);
        assertThat(repository.target).isEqualTo("old");
        assertThat(repository.expected).isEqualTo("current");
    }

    @Test
    void rejectsAMissingOrUnretainedTargetBeforeChangingTheCurrentVersion() {
        RecordingRepository repository = new RecordingRepository();
        repository.chunks = List.of();

        assertThatThrownBy(() -> new KnowledgeIndexRollbackService(repository).rollback("old", "current"))
                .isInstanceOf(KnowledgeIndexPublicationException.class)
                .hasMessageContaining("retained");
        assertThat(repository.target).isNull();
    }

    private static final class RecordingRepository implements KnowledgeIndexRepository {
        private final KnowledgeIndexVersion old = new KnowledgeIndexVersion("old", KnowledgeIndexStatus.RETIRED,
                Instant.now(), Instant.now(), "current");
        private final KnowledgeIndexVersion current = new KnowledgeIndexVersion("current", KnowledgeIndexStatus.PUBLISHED,
                Instant.now(), Instant.now(), "old");
        private List<KnowledgeChunk> chunks = List.of(KnowledgeChunk.create(
                "doc", "v1", 0, "retained", "Risk", java.util.Map.of()));
        private String target;
        private String expected;
        private boolean restored;

        @Override
        public void save(KnowledgeIndexVersion version) {
        }

        @Override
        public Optional<KnowledgeIndexVersion> find(String indexVersion) {
            return switch (indexVersion) {
                case "old" -> restored
                        ? Optional.of(new KnowledgeIndexVersion("old", KnowledgeIndexStatus.PUBLISHED,
                        old.createdAt(), Instant.now(), old.replacedVersion()))
                        : Optional.of(old);
                case "current" -> Optional.of(current);
                default -> Optional.empty();
            };
        }

        @Override
        public Optional<KnowledgeIndexVersion> findPublished() {
            return Optional.of(current);
        }

        @Override
        public List<KnowledgeChunk> findChunks(String indexVersion) {
            return "old".equals(indexVersion) ? chunks : List.of();
        }

        @Override
        public void markEvaluated(String indexVersion, RagEvaluationResult result) {
        }

        @Override
        public void markFailed(String indexVersion) {
        }

        @Override
        public void compareAndPublish(String candidateVersion, String expectedCurrentVersion) {
        }

        @Override
        public void restoreAndPublish(String targetVersion, String expectedCurrentVersion) {
            target = targetVersion;
            expected = expectedCurrentVersion;
            restored = true;
        }
    }
}
