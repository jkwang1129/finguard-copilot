package com.finguard.copilot.knowledge.index;

import java.util.List;
import java.util.Optional;

import com.finguard.copilot.foundation.stub.InMemoryVectorStore;
import com.finguard.copilot.knowledge.domain.KnowledgeChunk;
import com.finguard.copilot.knowledge.domain.KnowledgeIndexStatus;
import com.finguard.copilot.knowledge.domain.KnowledgeIndexVersion;
import com.finguard.copilot.knowledge.persistence.KnowledgeIndexRepository;
import com.finguard.copilot.knowledge.port.EmbeddingGateway;
import com.finguard.copilot.knowledge.port.EmbeddingVector;
import com.finguard.copilot.knowledge.port.VectorDocument;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class KnowledgeIndexEvaluationServiceTest {

    @Test
    void evaluatesRecallCitationAndNoAnswerDeterministicallyOnTheCandidateIndex() {
        InMemoryVectorStore vectorStore = new InMemoryVectorStore();
        vectorStore.createIndex("candidate", 2);
        KnowledgeChunk relevant = KnowledgeChunk.create("doc-1", "v1", 0, "risk policy", "Risk", java.util.Map.of());
        KnowledgeChunk forbidden = KnowledgeChunk.create("doc-2", "v1", 0, "other policy", "Other", java.util.Map.of());
        vectorStore.upsert("candidate", List.of(
                new VectorDocument(relevant.chunkId(), "candidate", relevant.content(), new EmbeddingVector(List.of(1.0, 0.0)),
                        java.util.Map.of("documentId", "doc-1", "documentVersion", "v1")),
                new VectorDocument(forbidden.chunkId(), "candidate", forbidden.content(), new EmbeddingVector(List.of(0.0, 1.0)),
                        java.util.Map.of("documentId", "doc-2", "documentVersion", "v1"))));
        RecordingRepository repository = new RecordingRepository();
        KnowledgeIndexEvaluationService service = new KnowledgeIndexEvaluationService(
                new QueryEmbeddingGateway(), vectorStore, repository, "embedding-v1", "rag-evaluator-v1", 1, 0.8);

        RagEvaluationResult result = service.evaluate("candidate", List.of(
                new RagCase("answerable", "risk", List.of(relevant.chunkId()), "doc-1", "v1", List.of(forbidden.chunkId())),
                new RagCase("no-answer", "unknown", List.of(), null, null, List.of(forbidden.chunkId()))));

        assertThat(result.recallAtK()).isEqualTo(1.0);
        assertThat(result.citationPrecision()).isEqualTo(1.0);
        assertThat(result.noAnswerCorrectness()).isEqualTo(1.0);
        assertThat(repository.evaluation).isEqualTo(result);
    }

    private static final class QueryEmbeddingGateway implements EmbeddingGateway {
        @Override
        public EmbeddingVector embed(String text, String embeddingVersion) {
            return new EmbeddingVector("risk".equals(text) ? List.of(1.0, 0.0) : List.of(0.0, 0.0));
        }
    }

    private static final class RecordingRepository implements KnowledgeIndexRepository {
        private RagEvaluationResult evaluation;

        @Override
        public void save(KnowledgeIndexVersion version) {
        }

        @Override
        public Optional<KnowledgeIndexVersion> find(String indexVersion) {
            return Optional.of(new KnowledgeIndexVersion(indexVersion, KnowledgeIndexStatus.EVALUATING,
                    java.time.Instant.now(), null, null));
        }

        @Override
        public Optional<KnowledgeIndexVersion> findPublished() {
            return Optional.empty();
        }

        @Override
        public void markEvaluated(String indexVersion, RagEvaluationResult result) {
            evaluation = result;
        }

        @Override
        public void markFailed(String indexVersion) {
        }

        @Override
        public void compareAndPublish(String candidateVersion, String expectedCurrentVersion) {
        }
    }
}
