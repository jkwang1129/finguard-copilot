package com.finguard.copilot.knowledge.index;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.finguard.copilot.foundation.stub.FixedEmbeddingGateway;
import com.finguard.copilot.knowledge.domain.KnowledgeChunk;
import com.finguard.copilot.knowledge.domain.KnowledgeIndexVersion;
import com.finguard.copilot.knowledge.ingest.DocumentChunker;
import com.finguard.copilot.knowledge.ingest.DocumentCleaner;
import com.finguard.copilot.knowledge.ingest.KnowledgeAdmissionPolicy;
import com.finguard.copilot.knowledge.ingest.KnowledgeIngestionService;
import com.finguard.copilot.knowledge.persistence.JdbcKnowledgeDocumentRepository;
import com.finguard.copilot.knowledge.persistence.JdbcKnowledgeIndexRepository;
import com.finguard.copilot.knowledge.port.EmbeddingGateway;
import com.finguard.copilot.knowledge.port.VectorSearchQuery;
import com.finguard.copilot.knowledge.qdrant.QdrantConfiguration;
import com.finguard.copilot.knowledge.qdrant.QdrantHttpClient;
import com.finguard.copilot.knowledge.qdrant.QdrantVectorStore;
import com.finguard.copilot.knowledge.port.VectorStore;
import com.finguard.copilot.knowledge.retrieval.KnowledgeRetrievalService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.containers.wait.strategy.Wait;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "COPILOT_DB_URL=jdbc:mysql://unused/test",
        "COPILOT_DB_USERNAME=test-only",
        "COPILOT_DB_PASSWORD=",
        "spring.datasource.hikari.maximum-pool-size=4",
        "spring.datasource.hikari.connection-timeout=5000"
})
@Testcontainers
class KnowledgeIndexChangeIntegrationTest {

    @Container
    @ServiceConnection
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4.10")
            .withCommand("--log-bin-trust-function-creators=1");

    @Container
    static final GenericContainer<?> QDRANT = new GenericContainer<>("qdrant/qdrant:v1.15.5")
            .withExposedPorts(6333)
            .waitingFor(Wait.forHttp("/healthz").forPort(6333));

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private JdbcKnowledgeDocumentRepository documentRepository;

    @Autowired
    private JdbcKnowledgeIndexRepository indexRepository;

    private final EmbeddingGateway embeddings = new FixedEmbeddingGateway(4);
    private VectorStore vectorStore;
    private KnowledgeIngestionService ingestion;
    private KnowledgeIndexBuildService builder;
    private final Deque<String> candidateVersions = new ArrayDeque<>(List.of("candidate-v2", "candidate-v3"));

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM knowledge_citation");
        jdbc.update("DELETE FROM knowledge_index_membership");
        jdbc.update("DELETE FROM knowledge_index_version");
        jdbc.update("DELETE FROM knowledge_chunk");
        jdbc.update("DELETE FROM knowledge_document");
        ObjectMapper mapper = new ObjectMapper();
        QdrantHttpClient client = new QdrantHttpClient(new QdrantConfiguration(
                URI.create("http://" + QDRANT.getHost() + ":" + QDRANT.getMappedPort(6333)),
                "finguard-change-test", Duration.ofSeconds(3), Duration.ofSeconds(5), 4), mapper);
        vectorStore = new QdrantVectorStore(client, "finguard-change-test");
        ingestion = new KnowledgeIngestionService(new DocumentCleaner(), new DocumentChunker(80, 8),
                new KnowledgeAdmissionPolicy(), documentRepository, Instant::now);
        builder = new KnowledgeIndexBuildService(embeddings, vectorStore, indexRepository,
                "embedding-v1", Instant::now);
    }

    @Test
    void updateDeleteAndRollbackUseNewVersionsAndPreserveHistoricalVectors() {
        ingestion.register(new com.finguard.copilot.knowledge.ingest.KnowledgeDocumentRequest(
                "doc-1", com.finguard.copilot.knowledge.domain.KnowledgeSourceType.POLICY,
                "Risk policy", "v1", "# Risk\n\nold policy"));
        ingestion.register(new com.finguard.copilot.knowledge.ingest.KnowledgeDocumentRequest(
                "doc-2", com.finguard.copilot.knowledge.domain.KnowledgeSourceType.MANUAL,
                "Audit manual", "v1", "# Audit\n\nstable policy"));
        List<KnowledgeChunk> initialChunks = new ArrayList<>();
        initialChunks.addAll(documentRepository.findChunks("doc-1", "v1"));
        initialChunks.addAll(documentRepository.findChunks("doc-2", "v1"));

        builder.build("published-v1", initialChunks);
        indexRepository.markEvaluated("published-v1", perfect("published-v1"));
        indexRepository.compareAndPublish("published-v1", null);
        KnowledgeRetrievalService retrieval = new KnowledgeRetrievalService(
                indexRepository, embeddings, vectorStore, "embedding-v1", 3, -1.0, documentRepository);
        assertThat(retrieval.retrieve("old policy", 1)).singleElement()
                .extracting(evidence -> evidence.excerpt()).isEqualTo("# Risk\nold policy");

        KnowledgeIndexChangeService changes = new KnowledgeIndexChangeService(
                indexRepository, documentRepository, ingestion, builder, candidateVersions::removeFirst);
        KnowledgeIndexPublicationService publication = new KnowledgeIndexPublicationService(
                indexRepository, new PublicationThresholds(0.0, 0.0, 0.0));

        KnowledgeIndexVersion updated = changes.replaceDocument("doc-1", "v2",
                "# Risk\n\nnew policy".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        List<KnowledgeChunk> updatedChunks = indexRepository.findChunks(updated.indexVersion());
        assertThat(updatedChunks).extracting(KnowledgeChunk::chunkId)
                .containsExactlyInAnyOrder("doc-2:v1:0", "doc-1:v2:0");
        publishCandidate(publication, updated, updatedChunks.stream()
                .filter(chunk -> chunk.documentId().equals("doc-1"))
                .findFirst().orElseThrow());

        KnowledgeChunk oldChunk = documentRepository.findChunks("doc-1", "v1").get(0);
        assertThat(vectorStore.search(new VectorSearchQuery("published-v1",
                embeddings.embed(oldChunk.content(), "embedding-v1"), 5)))
                .extracting(result -> result.document().id()).contains(oldChunk.chunkId());
        assertThat(retrieval.retrieve("new policy", 2))
                .extracting(evidence -> evidence.excerpt()).contains("# Risk\nnew policy");

        KnowledgeIndexVersion deleted = changes.deleteDocument("doc-1", "v2");
        List<KnowledgeChunk> deletedChunks = indexRepository.findChunks(deleted.indexVersion());
        assertThat(deletedChunks).extracting(KnowledgeChunk::documentId).containsExactly("doc-2");
        publishCandidate(publication, deleted, deletedChunks.get(0));
        assertThat(indexRepository.findPublished()).get()
                .extracting(KnowledgeIndexVersion::indexVersion).isEqualTo("candidate-v3");

        KnowledgeIndexVersion rolledBack = new KnowledgeIndexRollbackService(indexRepository)
                .rollback("published-v1", "candidate-v3");
        assertThat(rolledBack.indexVersion()).isEqualTo("published-v1");
        assertThat(indexRepository.findChunks("published-v1")).extracting(KnowledgeChunk::chunkId)
                .containsExactly("doc-1:v1:0", "doc-2:v1:0");
        assertThat(indexRepository.findPublished()).get()
                .extracting(KnowledgeIndexVersion::indexVersion).isEqualTo("published-v1");
    }

    private void publishCandidate(KnowledgeIndexPublicationService publication,
                                  KnowledgeIndexVersion candidate, KnowledgeChunk expectedChunk) {
        RagCase evaluationCase = new RagCase("case-" + candidate.indexVersion(), expectedChunk.content(),
                List.of(expectedChunk.chunkId()), expectedChunk.documentId(), expectedChunk.documentVersion(), List.of());
        RagEvaluationResult result = new KnowledgeIndexEvaluationService(
                embeddings, vectorStore, indexRepository, "embedding-v1", "rag-evaluator-v1", 1, -1.0)
                .evaluate(candidate.indexVersion(), List.of(evaluationCase));
        publication.publish(candidate.indexVersion(), result);
    }

    private RagEvaluationResult perfect(String version) {
        return new RagEvaluationResult(version, 1.0, 1.0, 1.0, "rag-evaluator-v1", 1);
    }
}
