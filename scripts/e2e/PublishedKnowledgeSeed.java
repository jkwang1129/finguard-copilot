import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finguard.copilot.foundation.stub.FixedEmbeddingGateway;
import com.finguard.copilot.knowledge.domain.KnowledgeSourceType;
import com.finguard.copilot.knowledge.ingest.*;
import com.finguard.copilot.knowledge.index.*;
import com.finguard.copilot.knowledge.persistence.*;
import com.finguard.copilot.knowledge.qdrant.*;
import com.finguard.copilot.knowledge.retrieval.KnowledgeRetrievalService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Local acceptance CLI: uses the same admission/build/evaluation/publication services as the application. */
public class PublishedKnowledgeSeed {
    public static void main(String[] args) throws Exception {
        var dataSource = new DriverManagerDataSource(System.getenv("COPILOT_DB_URL"),
                System.getenv("COPILOT_DB_USERNAME"), System.getenv("COPILOT_DB_PASSWORD"));
        var jdbc = new JdbcTemplate(dataSource);
        var transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        var mapper = new ObjectMapper();
        var documents = new JdbcKnowledgeDocumentRepository(jdbc, mapper);
        var indexes = new JdbcKnowledgeIndexRepository(jdbc);
        boolean semantic="semantic".equals(System.getenv("COPILOT_EMBEDDING_MODE"));
        int dimensions=semantic?384:8;
        String embeddingVersion=semantic?System.getenv("COPILOT_EMBEDDING_VERSION"):"fixed-v1";
        com.finguard.copilot.knowledge.port.EmbeddingGateway embedding=semantic?
            new com.finguard.copilot.knowledge.embedding.LoopbackSemanticEmbeddingGateway(
                URI.create(System.getenv("COPILOT_EMBEDDING_ENDPOINT")),embeddingVersion,System.getenv("COPILOT_EMBEDDING_MANIFEST_SHA256"),384,Duration.ofSeconds(10)):
            new FixedEmbeddingGateway(8);
        var configuration = new QdrantConfiguration(URI.create(System.getenv("COPILOT_QDRANT_ENDPOINT")),
                System.getenv("COPILOT_QDRANT_COLLECTION_PREFIX"), Duration.ofSeconds(2), Duration.ofSeconds(5), dimensions);
        var vectors = new QdrantVectorStore(new QdrantHttpClient(configuration, mapper), configuration.collectionPrefix());
        String version = System.getenv("E2E_INDEX_VERSION");
        String documentId = com.finguard.copilot.guardrail.CoreLargeAmountPolicyGuard.DOCUMENT_ID;
        String policy;
        try (var input = PublishedKnowledgeSeed.class.getResourceAsStream("/knowledge/large-amount-review-policy-v1.txt")) {
            if (input == null) throw new IllegalStateException("Project review policy resource is unavailable");
            policy = new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
        var canonical = new DocumentCleaner().canonicalize(policy);
        if (!com.finguard.copilot.guardrail.CoreLargeAmountPolicyGuard.CONTENT_SHA256.equals(canonical.checksum())) {
            throw new IllegalStateException("Project review policy checksum mismatch");
        }
        if ("true".equals(System.getenv("P4_SEED_OTHER_POLICY"))) {
            documentId = "isolated-other-policy";
            policy = "Human review considers available evidence for other cases.";
        }
        final String selectedDocumentId = documentId;
        final String selectedPolicy = policy;
        var ingestion = new KnowledgeIngestionService(new DocumentCleaner(), new DocumentChunker(2048, 0),
                new KnowledgeAdmissionPolicy(), documents, Instant::now);
        transactions.executeWithoutResult(status -> ingestion.register(new KnowledgeDocumentRequest(selectedDocumentId,
                KnowledgeSourceType.POLICY, "Isolated review policy", "v1", selectedPolicy)));
        var chunks = documents.findChunks(documentId, "v1");
        transactions.executeWithoutResult(status -> new KnowledgeIndexBuildService(embedding, vectors,
                indexes, embeddingVersion, Instant::now).build(version, chunks));
        var evaluation = new KnowledgeIndexEvaluationService(embedding, vectors, indexes,
                embeddingVersion, "e2e-smoke-v1", 1, -1.0).evaluate(version, List.of(new RagCase(
                "large-amount", "LARGE_AMOUNT AMOUNT_AT_OR_ABOVE_THRESHOLD",
                List.of(chunks.get(0).chunkId()), documentId, "v1", List.of())));
        transactions.executeWithoutResult(status -> new KnowledgeIndexPublicationService(indexes,
                new PublicationThresholds(1.0, 1.0, 1.0)).publish(version, evaluation));
        var evidence = new KnowledgeRetrievalService(indexes, embedding, vectors, embeddingVersion, 1, -1.0,
                documents).retrieve("LARGE_AMOUNT AMOUNT_AT_OR_ABOVE_THRESHOLD", 1);
        if (evidence.size() != 1 || !version.equals(evidence.get(0).citation().indexVersion())) {
            throw new IllegalStateException("Published evidence retrieval did not match the seeded index");
        }
        System.out.println(mapper.writeValueAsString(java.util.Map.of(
                "indexVersion", version, "status", indexes.findPublished().orElseThrow().status().name(),
                "documentId", documentId, "chunkCount", chunks.size(),
                "retrievedChunk", evidence.get(0).citation().chunkId(),
                "recallAtK", evaluation.recallAtK(), "citationPrecision", evaluation.citationPrecision(),
                "embeddingVersion", embeddingVersion, "scope", semantic?"pinned semantic embedding, single-document process smoke, not broad retrieval quality":"synthetic smoke case, not semantic quality evaluation")));
    }
}
