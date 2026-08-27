package com.finguard.copilot.knowledge.index;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.finguard.copilot.knowledge.domain.KnowledgeIndexStatus;
import com.finguard.copilot.knowledge.domain.KnowledgeIndexVersion;
import com.finguard.copilot.knowledge.persistence.KnowledgeIndexRepository;
import com.finguard.copilot.knowledge.port.EmbeddingGateway;
import com.finguard.copilot.knowledge.port.EmbeddingVector;
import com.finguard.copilot.knowledge.port.VectorDocument;
import com.finguard.copilot.knowledge.port.VectorSearchQuery;
import com.finguard.copilot.knowledge.port.VectorSearchResult;
import com.finguard.copilot.knowledge.port.VectorStore;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class KnowledgeIndexFailureIsolationTest {

    @Test
    void embeddingFailureMarksCandidateFailedAndDoesNotPublishIt() {
        RecordingRepository repository = new RecordingRepository();
        KnowledgeIndexBuildService service = new KnowledgeIndexBuildService(
                (text, version) -> { throw new IllegalStateException("embedding unavailable"); },
                new RecordingVectorStore(), repository, "embedding-v1", Instant::now);

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.build("candidate", List.of(
                com.finguard.copilot.knowledge.domain.KnowledgeChunk.create(
                        "doc", "v1", 0, "policy", "Risk", Map.of()))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("embedding unavailable");
        assertThat(repository.failed).isEqualTo("candidate");
        assertThat(repository.findPublished()).isEmpty();
    }

    @Test
    void stalePublicationDoesNotMoveThePublishedPointer() {
        StaleRepository repository = new StaleRepository();
        KnowledgeIndexPublicationService service = new KnowledgeIndexPublicationService(
                repository, new PublicationThresholds(0.8, 0.8, 0.8));

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.publish("candidate", new RagEvaluationResult(
                "candidate", 1.0, 1.0, 1.0, "rag-evaluator-v1", 1)))
                .isInstanceOf(KnowledgeIndexPublicationException.class)
                .hasMessageContaining("stale");
        assertThat(repository.findPublished()).get().extracting(KnowledgeIndexVersion::indexVersion)
                .isEqualTo("new-current");
    }

    private static final class StaleRepository implements KnowledgeIndexRepository {
        @Override
        public void save(KnowledgeIndexVersion version) {
        }

        @Override
        public Optional<KnowledgeIndexVersion> find(String indexVersion) {
            return Optional.empty();
        }

        @Override
        public Optional<KnowledgeIndexVersion> findPublished() {
            return Optional.of(new KnowledgeIndexVersion("new-current", KnowledgeIndexStatus.PUBLISHED,
                    Instant.now(), Instant.now(), null));
        }

        @Override
        public void markFailed(String indexVersion) {
        }

        @Override
        public void markEvaluated(String indexVersion, RagEvaluationResult result) {
        }

        @Override
        public void compareAndPublish(String candidateVersion, String expectedCurrentVersion) {
            throw new IllegalStateException("stale publication request");
        }
    }

    private static final class RecordingRepository implements KnowledgeIndexRepository {
        private String failed;

        @Override
        public void save(KnowledgeIndexVersion version) {
        }

        @Override
        public Optional<KnowledgeIndexVersion> find(String indexVersion) {
            return Optional.empty();
        }

        @Override
        public Optional<KnowledgeIndexVersion> findPublished() {
            return Optional.empty();
        }

        @Override
        public void markEvaluated(String indexVersion, RagEvaluationResult result) {
        }

        @Override
        public void markFailed(String indexVersion) {
            failed = indexVersion;
        }

        @Override
        public void compareAndPublish(String candidateVersion, String expectedCurrentVersion) {
        }
    }

    private static final class RecordingVectorStore implements VectorStore {
        @Override
        public void createIndex(String indexVersion, int dimension) {
        }

        @Override
        public void upsert(VectorDocument document) {
        }

        @Override
        public void upsert(String indexVersion, List<VectorDocument> documents) {
        }

        @Override
        public List<VectorSearchResult> search(VectorSearchQuery query) {
            return List.of();
        }

        @Override
        public void deleteIndex(String indexVersion) {
        }

        @Override
        public void clear() {
        }
    }
}
