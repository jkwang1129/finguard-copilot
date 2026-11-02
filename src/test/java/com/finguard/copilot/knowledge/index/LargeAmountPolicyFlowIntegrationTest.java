package com.finguard.copilot.knowledge.index;

import java.math.BigDecimal;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.finguard.copilot.analysis.application.ModelAnalysisOrchestrator;
import com.finguard.copilot.analysis.application.ModelInvocationException;
import com.finguard.copilot.analysis.domain.AnalysisRecommendation;
import com.finguard.copilot.analysis.domain.AnalysisResult;
import com.finguard.copilot.config.CopilotProperties;
import com.finguard.copilot.config.SecretValue;
import com.finguard.copilot.context.ContextAssembler;
import com.finguard.copilot.context.FactSnapshot;
import com.finguard.copilot.context.PromptTemplate;
import com.finguard.copilot.context.TokenBudget;
import com.finguard.copilot.context.TokenEstimator;
import com.finguard.copilot.coreclient.port.ReviewTaskContext;
import com.finguard.copilot.foundation.stub.FixedEmbeddingGateway;
import com.finguard.copilot.guardrail.CoreLargeAmountPolicyGuard;
import com.finguard.copilot.knowledge.domain.KnowledgeSourceType;
import com.finguard.copilot.knowledge.ingest.DocumentChunker;
import com.finguard.copilot.knowledge.ingest.DocumentCleaner;
import com.finguard.copilot.knowledge.ingest.KnowledgeAdmissionPolicy;
import com.finguard.copilot.knowledge.ingest.KnowledgeDocumentRequest;
import com.finguard.copilot.knowledge.ingest.KnowledgeIngestionService;
import com.finguard.copilot.knowledge.persistence.JdbcKnowledgeDocumentRepository;
import com.finguard.copilot.knowledge.persistence.JdbcKnowledgeIndexRepository;
import com.finguard.copilot.knowledge.qdrant.QdrantConfiguration;
import com.finguard.copilot.knowledge.qdrant.QdrantHttpClient;
import com.finguard.copilot.knowledge.qdrant.QdrantVectorStore;
import com.finguard.copilot.knowledge.retrieval.KnowledgeRetrievalService;
import com.finguard.copilot.model.port.ModelGateway;
import com.finguard.copilot.model.port.ModelResponse;
import com.finguard.copilot.model.port.ModelUsage;
import com.finguard.copilot.model.validation.StructuredAnalysisParser;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(properties = {"COPILOT_DB_URL=jdbc:mysql://unused/test", "COPILOT_DB_USERNAME=test-only",
        "COPILOT_DB_PASSWORD=", "spring.datasource.hikari.maximum-pool-size=4"})
@Testcontainers
class LargeAmountPolicyFlowIntegrationTest {
    @Container @ServiceConnection
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4.10")
            .withCommand("--log-bin-trust-function-creators=1");
    @Container
    static final GenericContainer<?> QDRANT = new GenericContainer<>("qdrant/qdrant:v1.15.5")
            .withExposedPorts(6333).waitingFor(Wait.forHttp("/healthz").forPort(6333));
    @Autowired JdbcKnowledgeDocumentRepository documents;
    @Autowired JdbcKnowledgeIndexRepository indexes;
    private static final Instant NOW = Instant.parse("2026-10-28T00:00:00Z");
    private static final UUID ID = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");

    @Test
    void publishRetrieveAndEnforcePolicyWithRealMysqlAndQdrant() throws Exception {
        String policy;
        try (var stream = getClass().getResourceAsStream("/knowledge/large-amount-review-policy-v1.txt")) {
            policy = new String(stream.readAllBytes(), StandardCharsets.UTF_8).trim();
        }
        var embedding = new FixedEmbeddingGateway(8);
        var configuration = new QdrantConfiguration(
                URI.create("http://" + QDRANT.getHost() + ":" + QDRANT.getMappedPort(6333)),
                "p4-policy-isolated", Duration.ofSeconds(3), Duration.ofSeconds(5), 8);
        var vectors = new QdrantVectorStore(new QdrantHttpClient(configuration, new ObjectMapper()), "p4-policy-isolated");
        var ingestion = new KnowledgeIngestionService(new DocumentCleaner(), new DocumentChunker(2048, 0),
                new KnowledgeAdmissionPolicy(), documents, Instant::now);
        ingestion.register(new KnowledgeDocumentRequest(CoreLargeAmountPolicyGuard.DOCUMENT_ID,
                KnowledgeSourceType.POLICY, "Project large amount policy", "v1", policy));
        var chunks = documents.findChunks(CoreLargeAmountPolicyGuard.DOCUMENT_ID, "v1");
        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0).contentChecksum()).isEqualTo(CoreLargeAmountPolicyGuard.CONTENT_SHA256);
        new KnowledgeIndexBuildService(embedding, vectors, indexes, "fixed-v1", Instant::now).build("p4-policy-v1", chunks);
        var evaluation = new KnowledgeIndexEvaluationService(embedding, vectors, indexes, "fixed-v1",
                "p4-smoke-v1", 1, -1.0).evaluate("p4-policy-v1", List.of(new RagCase("case-1",
                "LARGE_AMOUNT AMOUNT_AT_OR_ABOVE_THRESHOLD", List.of(chunks.get(0).chunkId()),
                CoreLargeAmountPolicyGuard.DOCUMENT_ID, "v1", List.of())));
        new KnowledgeIndexPublicationService(indexes, new PublicationThresholds(1, 1, 1))
                .publish("p4-policy-v1", evaluation);
        var retrieval = new KnowledgeRetrievalService(indexes, embedding, vectors, "fixed-v1", 1, -1.0, documents);
        assertThat(retrieval.retrieve("LARGE_AMOUNT AMOUNT_AT_OR_ABOVE_THRESHOLD", 1))
                .singleElement().satisfies(item -> {
                    assertThat(item.excerpt()).isEqualTo(policy);
                    assertThat(item.citation().indexVersion()).isEqualTo("p4-policy-v1");
                });
        assertThat(orchestrator(retrieval, policy, AnalysisRecommendation.CONFIRMED)
                .execute(ID, snapshot("10000.00", true)).result().recommendation())
                .isEqualTo(AnalysisRecommendation.CONFIRMED);
        assertRejected(orchestrator(retrieval, policy, AnalysisRecommendation.IGNORED), snapshot("10000.00", true));
        assertRejected(orchestrator(retrieval, policy, AnalysisRecommendation.CONFIRMED), snapshot("15000.00", true));
        assertRejected(orchestrator(retrieval, policy, AnalysisRecommendation.CONFIRMED), snapshot("10000.00", false));

        ingestion.register(new KnowledgeDocumentRequest("other-policy", KnowledgeSourceType.POLICY,
                "Other policy", "v1", "Human review applies to other risk cases."));
        var otherChunks = documents.findChunks("other-policy", "v1");
        new KnowledgeIndexBuildService(embedding, vectors, indexes, "fixed-v1", Instant::now)
                .build("p4-without-policy", otherChunks);
        var otherEvaluation = new KnowledgeIndexEvaluationService(embedding, vectors, indexes, "fixed-v1",
                "p4-smoke-v1", 1, -1.0).evaluate("p4-without-policy", List.of(new RagCase("other-case",
                "other risk", List.of(otherChunks.get(0).chunkId()), "other-policy", "v1", List.of())));
        new KnowledgeIndexPublicationService(indexes, new PublicationThresholds(1, 1, 1))
                .publish("p4-without-policy", otherEvaluation);
        assertThat(retrieval.retrieve("LARGE_AMOUNT AMOUNT_AT_OR_ABOVE_THRESHOLD", 1))
                .singleElement().satisfies(item -> assertThat(item.citation().documentId()).isEqualTo("other-policy"));
        assertRejected(orchestrator(retrieval, otherChunks.get(0).content(), AnalysisRecommendation.CONFIRMED,
                "other-policy", otherChunks.get(0).sectionPath()), snapshot("10000.00", true));
    }

    private void assertRejected(ModelAnalysisOrchestrator orchestrator, FactSnapshot snapshot) {
        assertThatThrownBy(() -> orchestrator.execute(ID, snapshot))
                .isInstanceOfSatisfying(ModelInvocationException.class, exception ->
                        assertThat(exception.errorCode()).isEqualTo(com.finguard.copilot.common.error.ErrorCode.FACT_VALIDATION_FAILED));
    }

    private ModelAnalysisOrchestrator orchestrator(KnowledgeRetrievalService retrieval, String policy,
            AnalysisRecommendation recommendation) throws Exception {
        return orchestrator(retrieval, policy, recommendation, CoreLargeAmountPolicyGuard.DOCUMENT_ID, "Document");
    }

    private ModelAnalysisOrchestrator orchestrator(KnowledgeRetrievalService retrieval, String policy,
            AnalysisRecommendation recommendation, String documentId, String section) throws Exception {
        var result = new AnalysisResult(AnalysisResult.CURRENT_SCHEMA_VERSION, ID, "summary", "explanation",
                List.of(new AnalysisResult.BusinessEvidence(11, 42, new BigDecimal("12500.00"), "USD",
                        "LARGE_AMOUNT", "AMOUNT_AT_OR_ABOVE_THRESHOLD", List.of("transaction.amount"))),
                List.of(new AnalysisResult.PolicyCitation(documentId, "v1", section, policy)),
                recommendation, "review", List.of(), List.of());
        String json = new ObjectMapper().writeValueAsString(result);
        ModelGateway modelGateway = ignored -> new ModelResponse("stub", json, 10, 5, json,
                new ModelUsage(10, 5, Duration.ofMillis(1)), Duration.ofMillis(1), "stub");
        var model = new CopilotProperties.Model("stub", "stub", URI.create("https://example.invalid"),
                new SecretValue("test-only"), 32000, 2000, Duration.ofSeconds(10), "test-v1");
        return new ModelAnalysisOrchestrator(modelGateway,
                new ContextAssembler(TokenBudget.phase3Defaults(), TokenEstimator.conservative(),
                        new PromptTemplate("review-analysis-v1")), new StructuredAnalysisParser(), model,
                new CopilotProperties.Budget(32000, 2000, 1), retrieval, 1);
    }

    private FactSnapshot snapshot(String threshold, boolean present) {
        var facts = new ReviewTaskContext(new ReviewTaskContext.ReviewTask(7, 0, "PENDING", "LARGE_AMOUNT",
                "AMOUNT_AT_OR_ABOVE_THRESHOLD", NOW),
                new ReviewTaskContext.Transaction(11, 42, new BigDecimal("12500.00"), "USD", NOW, "CSV_IMPORT"),
                new ReviewTaskContext.Reconciliation("MATCHED", "REFERENCE_ID", "REASON"),
                present ? List.of(new ReviewTaskContext.RiskFact("OBSERVED_AMOUNT", "12500.00", "riskHit.observedAmount"),
                        new ReviewTaskContext.RiskFact("THRESHOLD_AMOUNT", threshold, "riskHit.thresholdAmount")) : List.of(),
                new ReviewTaskContext.AccountSummary("ACTIVE", "masked"), NOW);
        return new FactSnapshot(FactSnapshot.CURRENT_SCHEMA_VERSION, facts, NOW, NOW.plus(Duration.ofDays(30)),
                new byte[FactSnapshot.SHA_256_LENGTH]);
    }
}
