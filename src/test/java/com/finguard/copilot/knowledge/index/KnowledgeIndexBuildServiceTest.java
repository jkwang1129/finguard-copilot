package com.finguard.copilot.knowledge.index;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.finguard.copilot.foundation.stub.FixedEmbeddingGateway;
import com.finguard.copilot.foundation.stub.InMemoryVectorStore;
import com.finguard.copilot.knowledge.domain.KnowledgeChunk;
import com.finguard.copilot.knowledge.domain.KnowledgeIndexStatus;
import com.finguard.copilot.knowledge.domain.KnowledgeIndexVersion;
import com.finguard.copilot.knowledge.persistence.KnowledgeIndexRepository;
import com.finguard.copilot.knowledge.port.VectorDocument;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class KnowledgeIndexBuildServiceTest {

    @Test
    void buildsOnlyTheCandidateIndexAndEmbedsEveryCanonicalChunk() {
        RecordingRepository repository = new RecordingRepository();
        InMemoryVectorStore vectorStore = new InMemoryVectorStore();
        KnowledgeIndexBuildService service = new KnowledgeIndexBuildService(
                new FixedEmbeddingGateway(4), vectorStore, repository, "embedding-v1", Instant::now);

        KnowledgeIndexVersion result = service.build("candidate-1", List.of(
                KnowledgeChunk.create("policy", "v1", 0, "Escalate high risk reviews.", "Risk", Map.of()),
                KnowledgeChunk.create("policy", "v1", 1, "Record the reviewer decision.", "Audit", Map.of())));

        assertThat(result.indexVersion()).isEqualTo("candidate-1");
        assertThat(result.status()).isEqualTo(KnowledgeIndexStatus.EVALUATING);
        assertThat(repository.saved).extracting(KnowledgeIndexVersion::indexVersion)
                .containsExactly("candidate-1");
        assertThat(vectorStore.search(new com.finguard.copilot.knowledge.port.VectorSearchQuery(
                "candidate-1", new FixedEmbeddingGateway(4).embed("Escalate high risk reviews.", "embedding-v1"), 5)))
                .hasSize(2);
        assertThat(repository.failed).isEmpty();
    }

    private static final class RecordingRepository implements KnowledgeIndexRepository {
        private final List<KnowledgeIndexVersion> saved = new ArrayList<>();
        private final List<String> failed = new ArrayList<>();

        @Override
        public void save(KnowledgeIndexVersion version) {
            saved.add(version);
        }

        @Override
        public Optional<KnowledgeIndexVersion> find(String indexVersion) {
            return saved.stream().filter(value -> value.indexVersion().equals(indexVersion)).findFirst();
        }

        @Override
        public Optional<KnowledgeIndexVersion> findPublished() {
            return saved.stream().filter(value -> value.status() == KnowledgeIndexStatus.PUBLISHED).findFirst();
        }

        @Override
        public void markFailed(String indexVersion) {
            failed.add(indexVersion);
        }

        @Override
        public void markEvaluated(String indexVersion, RagEvaluationResult result) {
        }

        @Override
        public void compareAndPublish(String candidateVersion, String expectedCurrentVersion) {
        }
    }
}
