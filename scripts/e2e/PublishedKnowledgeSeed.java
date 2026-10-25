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
        var embedding = new FixedEmbeddingGateway(8);
        var configuration = new QdrantConfiguration(URI.create(System.getenv("COPILOT_QDRANT_ENDPOINT")),
                System.getenv("COPILOT_QDRANT_COLLECTION_PREFIX"), Duration.ofSeconds(2), Duration.ofSeconds(5), 8);
        var vectors = new QdrantVectorStore(new QdrantHttpClient(configuration, mapper), configuration.collectionPrefix());
        String version = System.getenv("E2E_INDEX_VERSION");
        String documentId = "e2e-large-amount-policy";
        var ingestion = new KnowledgeIngestionService(new DocumentCleaner(), new DocumentChunker(2048, 0),
                new KnowledgeAdmissionPolicy(), documents, Instant::now);
        transactions.executeWithoutResult(status -> ingestion.register(new KnowledgeDocumentRequest(documentId,
                KnowledgeSourceType.POLICY, "Synthetic large amount review policy", "v1",
                "# Large amount review\nLARGE_AMOUNT with AMOUNT_AT_OR_ABOVE_THRESHOLD requires human review. "
                        + "Compare the observed amount with the configured threshold using Core facts. "
                        + "When the threshold is reached and the risk evidence is consistent, recommend CONFIRMED. "
                        + "Only a human reviewer may make the final decision.")));
        var chunks = documents.findChunks(documentId, "v1");
        transactions.executeWithoutResult(status -> new KnowledgeIndexBuildService(embedding, vectors,
                indexes, "fixed-v1", Instant::now).build(version, chunks));
        var evaluation = new KnowledgeIndexEvaluationService(embedding, vectors, indexes,
                "fixed-v1", "e2e-smoke-v1", 1, -1.0).evaluate(version, List.of(new RagCase(
                "large-amount", "LARGE_AMOUNT AMOUNT_AT_OR_ABOVE_THRESHOLD",
                List.of(chunks.get(0).chunkId()), documentId, "v1", List.of())));
        transactions.executeWithoutResult(status -> new KnowledgeIndexPublicationService(indexes,
                new PublicationThresholds(1.0, 1.0, 1.0)).publish(version, evaluation));
        var evidence = new KnowledgeRetrievalService(indexes, embedding, vectors, "fixed-v1", 1, -1.0,
                documents).retrieve("LARGE_AMOUNT AMOUNT_AT_OR_ABOVE_THRESHOLD", 1);
        if (evidence.size() != 1 || !version.equals(evidence.get(0).citation().indexVersion())) {
            throw new IllegalStateException("Published evidence retrieval did not match the seeded index");
        }
        System.out.println(mapper.writeValueAsString(java.util.Map.of(
                "indexVersion", version, "status", indexes.findPublished().orElseThrow().status().name(),
                "documentId", documentId, "chunkCount", chunks.size(),
                "retrievedChunk", evidence.get(0).citation().chunkId(),
                "recallAtK", evaluation.recallAtK(), "citationPrecision", evaluation.citationPrecision(),
                "embeddingVersion", "fixed-v1", "scope", "synthetic smoke case, not semantic quality evaluation")));
    }
}
