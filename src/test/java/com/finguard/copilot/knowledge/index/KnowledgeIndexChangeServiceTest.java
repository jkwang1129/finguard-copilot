package com.finguard.copilot.knowledge.index;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.finguard.copilot.foundation.stub.FixedEmbeddingGateway;
import com.finguard.copilot.foundation.stub.InMemoryVectorStore;
import com.finguard.copilot.knowledge.domain.KnowledgeChunk;
import com.finguard.copilot.knowledge.domain.KnowledgeDocument;
import com.finguard.copilot.knowledge.domain.KnowledgeIndexStatus;
import com.finguard.copilot.knowledge.domain.KnowledgeIndexVersion;
import com.finguard.copilot.knowledge.domain.KnowledgeSourceType;
import com.finguard.copilot.knowledge.ingest.DocumentChunker;
import com.finguard.copilot.knowledge.ingest.DocumentCleaner;
import com.finguard.copilot.knowledge.ingest.KnowledgeAdmissionPolicy;
import com.finguard.copilot.knowledge.ingest.KnowledgeIngestionService;
import com.finguard.copilot.knowledge.persistence.KnowledgeDocumentRepository;
import com.finguard.copilot.knowledge.persistence.KnowledgeIndexRepository;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class KnowledgeIndexChangeServiceTest {

    @Test
    void replacementBuildsASeparateCandidateAndLeavesPublishedChunksUntouched() {
        InMemoryDocumentRepository documents = new InMemoryDocumentRepository();
        KnowledgeDocument oldDocument = KnowledgeDocument.register("doc-1", KnowledgeSourceType.POLICY,
                "Policy", "v1", "a".repeat(64), Instant.now());
        documents.documents.add(oldDocument);
        KnowledgeChunk oldChunk = KnowledgeChunk.create("doc-1", "v1", 0, "old policy", "Risk", Map.of());
        KnowledgeChunk stableChunk = KnowledgeChunk.create("doc-2", "v1", 0, "stable policy", "Audit", Map.of());
        documents.chunks.addAll(List.of(oldChunk, stableChunk));
        InMemoryIndexRepository indexes = new InMemoryIndexRepository(oldChunk, stableChunk);
        InMemoryVectorStore vectors = new InMemoryVectorStore();
        KnowledgeIngestionService ingestion = new KnowledgeIngestionService(
                new DocumentCleaner(), new DocumentChunker(80, 8), new KnowledgeAdmissionPolicy(), documents, Instant::now);
        KnowledgeIndexBuildService builder = new KnowledgeIndexBuildService(
                new FixedEmbeddingGateway(4), vectors, indexes, "embedding-v1", Instant::now);
        KnowledgeIndexChangeService service = new KnowledgeIndexChangeService(
                indexes, documents, ingestion, builder, () -> "candidate-v2");

        KnowledgeIndexVersion candidate = service.replaceDocument("doc-1", "v2",
                "# Risk\n\nnew policy".getBytes(java.nio.charset.StandardCharsets.UTF_8));

        assertThat(candidate.indexVersion()).isEqualTo("candidate-v2");
        assertThat(indexes.candidateChunks).extracting(KnowledgeChunk::chunkId)
                .containsExactly("doc-2:v1:0", "doc-1:v2:0");
        assertThat(indexes.findChunks("published-v1")).extracting(KnowledgeChunk::chunkId)
                .containsExactly("doc-1:v1:0", "doc-2:v1:0");
    }

    private static final class InMemoryDocumentRepository implements KnowledgeDocumentRepository {
        private final List<KnowledgeDocument> documents = new ArrayList<>();
        private final List<KnowledgeChunk> chunks = new ArrayList<>();

        @Override
        public Optional<KnowledgeDocument> findLatestByDocumentId(String documentId) {
            return documents.stream().filter(value -> value.documentId().equals(documentId))
                    .max(java.util.Comparator.comparing(KnowledgeDocument::sourceVersion));
        }

        @Override
        public List<KnowledgeChunk> findChunks(String documentId, String documentVersion) {
            return chunks.stream().filter(value -> value.documentId().equals(documentId))
                    .filter(value -> value.documentVersion().equals(documentVersion)).toList();
        }

        @Override
        public Optional<KnowledgeDocument> findByCanonicalChecksum(String documentId, String sourceVersion,
                                                                    String canonicalChecksum) {
            return documents.stream().filter(value -> value.documentId().equals(documentId))
                    .filter(value -> value.sourceVersion().equals(sourceVersion))
                    .filter(value -> value.canonicalChecksum().equals(canonicalChecksum)).findFirst();
        }

        @Override
        public void save(KnowledgeDocument document, List<KnowledgeChunk> chunks) {
            documents.add(document);
            this.chunks.addAll(chunks);
        }
    }

    private static final class InMemoryIndexRepository implements KnowledgeIndexRepository {
        private final KnowledgeIndexVersion published = new KnowledgeIndexVersion("published-v1",
                KnowledgeIndexStatus.PUBLISHED, Instant.now(), Instant.now(), null);
        private final List<KnowledgeChunk> publishedChunks;
        private List<KnowledgeChunk> candidateChunks = List.of();

        private InMemoryIndexRepository(KnowledgeChunk... chunks) {
            publishedChunks = List.of(chunks);
        }

        @Override
        public void save(KnowledgeIndexVersion version) {
        }

        @Override
        public void save(KnowledgeIndexVersion version, List<KnowledgeChunk> chunks) {
            candidateChunks = List.copyOf(chunks);
        }

        @Override
        public Optional<KnowledgeIndexVersion> find(String indexVersion) {
            return Optional.of(published);
        }

        @Override
        public Optional<KnowledgeIndexVersion> findPublished() {
            return Optional.of(published);
        }

        @Override
        public List<KnowledgeChunk> findChunks(String indexVersion) {
            return "published-v1".equals(indexVersion) ? publishedChunks : candidateChunks;
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
    }
}
