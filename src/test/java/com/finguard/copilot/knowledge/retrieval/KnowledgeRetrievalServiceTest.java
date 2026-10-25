package com.finguard.copilot.knowledge.retrieval;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.finguard.copilot.foundation.stub.FixedEmbeddingGateway;
import com.finguard.copilot.foundation.stub.InMemoryVectorStore;
import com.finguard.copilot.knowledge.domain.KnowledgeCitation;
import com.finguard.copilot.knowledge.domain.KnowledgeChunk;
import com.finguard.copilot.knowledge.domain.KnowledgeIndexStatus;
import com.finguard.copilot.knowledge.domain.KnowledgeIndexVersion;
import com.finguard.copilot.knowledge.persistence.KnowledgeDocumentRepository;
import com.finguard.copilot.knowledge.persistence.KnowledgeIndexRepository;
import com.finguard.copilot.knowledge.port.VectorDocument;
import com.finguard.copilot.knowledge.port.VectorSearchQuery;
import com.finguard.copilot.knowledge.port.VectorSearchResult;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class KnowledgeRetrievalServiceTest {

    @Test
    void retrievesOnlyTheSinglePublishedIndexAndBuildsValidatedEvidence() {
        InMemoryVectorStore vectors = new InMemoryVectorStore();
        vectors.createIndex("published", 4);
        String checksum = KnowledgeChunk.create("doc", "v1", 0, "risk policy", "Risk", Map.of()).contentChecksum();
        vectors.upsert("published", List.of(new VectorDocument("doc:v1:0", "published", "risk policy",
                new FixedEmbeddingGateway(4).embed("risk policy", "embedding-v1"), Map.of(
                        "documentId", "doc", "documentVersion", "v1", "chunkId", "doc:v1:0",
                        "sectionPath", "Risk", "contentChecksum", checksum))));
        KnowledgeRetrievalService service = new KnowledgeRetrievalService(
                new PublishedRepository(), new FixedEmbeddingGateway(4), vectors, "embedding-v1", 3, -1.0);

        List<KnowledgeEvidence> evidence = service.retrieve("risk policy", 1);

        assertThat(evidence).hasSize(1);
        assertThat(evidence.get(0).citation()).isEqualTo(new KnowledgeCitation(
                "doc", "v1", "doc:v1:0", "Risk", checksum,
                evidence.get(0).citation().score(), "published"));
    }

    @Test
    void failsClosedWhenNoPublishedIndexExists() {
        KnowledgeRetrievalService service = new KnowledgeRetrievalService(
                new EmptyRepository(), new FixedEmbeddingGateway(4), new InMemoryVectorStore(),
                "embedding-v1", 3, -1.0);

        assertThatThrownBy(() -> service.retrieve("risk", 1))
                .isInstanceOf(KnowledgeRetrievalException.class)
                .hasMessageContaining(KnowledgeRetrievalException.KNOWLEDGE_UNAVAILABLE);
    }

    @Test
    void resolvesQdrantMetadataOnlyResultsFromTheRegisteredChunk() {
        InMemoryVectorStore vectors = new InMemoryVectorStore();
        vectors.createIndex("published", 4);
        KnowledgeChunk chunk = KnowledgeChunk.create("doc", "v1", 0, "risk policy", "Risk", Map.of());
        vectors.upsert("published", List.of(new VectorDocument("doc:v1:0", "published", "",
                new FixedEmbeddingGateway(4).embed("risk policy", "embedding-v1"), Map.of(
                        "documentId", "doc", "documentVersion", "v1", "chunkId", "doc:v1:0",
                        "sectionPath", "Risk", "contentChecksum", chunk.contentChecksum()))));

        KnowledgeDocumentRepository documents = new KnowledgeDocumentRepository() {
            @Override
            public Optional<com.finguard.copilot.knowledge.domain.KnowledgeDocument> findByCanonicalChecksum(
                    String documentId, String sourceVersion, String canonicalChecksum) {
                return Optional.empty();
            }

            @Override
            public void save(com.finguard.copilot.knowledge.domain.KnowledgeDocument document,
                             List<KnowledgeChunk> chunks) {
            }

            @Override
            public List<KnowledgeChunk> findChunks(String documentId, String documentVersion) {
                return List.of(chunk);
            }
        };
        KnowledgeRetrievalService service = new KnowledgeRetrievalService(
                new PublishedRepository(), new FixedEmbeddingGateway(4), vectors, "embedding-v1", 3, -1.0,
                documents);

        assertThat(service.retrieve("risk policy", 1)).singleElement()
                .extracting(KnowledgeEvidence::excerpt).isEqualTo("risk policy");
    }

    private static class PublishedRepository implements KnowledgeIndexRepository {
        @Override
        public void save(KnowledgeIndexVersion version) {
        }

        @Override
        public Optional<KnowledgeIndexVersion> find(String indexVersion) {
            return Optional.of(new KnowledgeIndexVersion("published", KnowledgeIndexStatus.PUBLISHED,
                    Instant.now(), Instant.now(), null));
        }

        @Override
        public Optional<KnowledgeIndexVersion> findPublished() {
            return find("published");
        }

        @Override
        public void markEvaluated(String indexVersion, com.finguard.copilot.knowledge.index.RagEvaluationResult result) {
        }

        @Override
        public void markFailed(String indexVersion) {
        }

        @Override
        public void compareAndPublish(String candidateVersion, String expectedCurrentVersion) {
        }
    }

    private static final class EmptyRepository extends PublishedRepository {
        @Override
        public Optional<KnowledgeIndexVersion> findPublished() {
            return Optional.empty();
        }
    }
}
