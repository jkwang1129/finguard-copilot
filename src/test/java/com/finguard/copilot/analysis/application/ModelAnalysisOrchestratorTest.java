package com.finguard.copilot.analysis.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.finguard.copilot.analysis.domain.AnalysisResult;
import com.finguard.copilot.config.CopilotProperties;
import com.finguard.copilot.config.SecretValue;
import com.finguard.copilot.context.ContextAssembler;
import com.finguard.copilot.context.FactSnapshot;
import com.finguard.copilot.context.PromptTemplate;
import com.finguard.copilot.context.SnapshotCodec;
import com.finguard.copilot.context.TokenBudget;
import com.finguard.copilot.context.TokenEstimator;
import com.finguard.copilot.coreclient.port.ReviewTaskContext;
import com.finguard.copilot.foundation.stub.FixedEmbeddingGateway;
import com.finguard.copilot.foundation.stub.InMemoryVectorStore;
import com.finguard.copilot.guardrail.AnalysisOutputSchemaValidator;
import com.finguard.copilot.knowledge.domain.KnowledgeChunk;
import com.finguard.copilot.knowledge.domain.KnowledgeIndexStatus;
import com.finguard.copilot.knowledge.domain.KnowledgeIndexVersion;
import com.finguard.copilot.knowledge.persistence.KnowledgeIndexRepository;
import com.finguard.copilot.knowledge.port.VectorDocument;
import com.finguard.copilot.knowledge.retrieval.KnowledgeRetrievalService;
import com.finguard.copilot.model.port.ModelGateway;
import com.finguard.copilot.model.port.ModelRequest;
import com.finguard.copilot.model.port.ModelResponse;
import com.finguard.copilot.model.port.ModelUsage;
import com.finguard.copilot.model.validation.StructuredAnalysisParser;
import org.junit.jupiter.api.Test;

class ModelAnalysisOrchestratorTest {

    private static final UUID ANALYSIS_ID = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final Instant CAPTURED_AT = Instant.parse("2026-08-31T06:00:00Z");

    @Test
    void assemblesVersionedContextAndReturnsModelUsageForWorkerPersistence() throws Exception {
        AtomicReference<ModelRequest> request = new AtomicReference<>();
        String output = validOutput();
        ModelGateway gateway = modelRequest -> {
            request.set(modelRequest);
            return new ModelResponse("qwen-plus", output, 120, 25, output,
                    new ModelUsage(120, 25, Duration.ofMillis(42)), Duration.ofMillis(42), "qwen");
        };
        CopilotProperties.Model model = new CopilotProperties.Model(
                "qwen", "qwen-plus", URI.create("https://model.example.internal"),
                new SecretValue("secret-for-test-only"), 32000, 2000, Duration.ofSeconds(20),
                "phase3-2026-08");
        ContextAssembler assembler = new ContextAssembler(
                TokenBudget.phase3Defaults(), TokenEstimator.conservative(),
                new PromptTemplate("review-analysis-v1"));
        ModelAnalysisOrchestrator orchestrator = new ModelAnalysisOrchestrator(
                gateway, assembler, new StructuredAnalysisParser(new ObjectMapper(),
                new AnalysisOutputSchemaValidator()), model, new CopilotProperties.Budget(32000, 2000, 1));

        AnalysisExecution execution = orchestrator.execute(ANALYSIS_ID, snapshot());

        assertThat(execution.result().analysisId()).isEqualTo(ANALYSIS_ID);
        assertThat(execution.provider()).isEqualTo("qwen");
        assertThat(execution.modelConfigurationVersion()).contains("qwen-plus");
        assertThat(execution.promptVersion()).isEqualTo("review-analysis-v1");
        assertThat(execution.usage()).isEqualTo(new ModelUsage(120, 25, Duration.ofMillis(42)));
        assertThat(request.get().systemInstruction()).contains("SYSTEM_POLICY");
        assertThat(request.get().context()).contains("analysisId=" + ANALYSIS_ID);
        assertThat(request.get().context()).contains("CORE_FACTS", "12500.00", "REVIEWER_INPUT");
        assertThat(request.get().outputSchemaJson()).contains("analysisId", "recommendation");
        assertThat(request.get().maxOutputTokens()).isEqualTo(2000);
        assertThat(request.get().promptVersion()).isEqualTo("review-analysis-v1");
    }

    @Test
    void rejectsDirectionalModelOutputWhenNoPublishedKnowledgeWasRetrieved() throws Exception {
        String output = validDirectionalOutput();
        ModelGateway gateway = modelRequest -> new ModelResponse("qwen-plus", output,
                120, 25, output,
                new ModelUsage(120, 25, Duration.ofMillis(42)), Duration.ofMillis(42), "qwen");
        CopilotProperties.Model model = new CopilotProperties.Model(
                "qwen", "qwen-plus", URI.create("https://model.example.internal"),
                new SecretValue("secret-for-test-only"), 32000, 2000, Duration.ofSeconds(20),
                "phase3-2026-08");
        ContextAssembler assembler = new ContextAssembler(
                TokenBudget.phase3Defaults(), TokenEstimator.conservative(),
                new PromptTemplate("review-analysis-v1"));
        ModelAnalysisOrchestrator orchestrator = new ModelAnalysisOrchestrator(
                gateway, assembler, new StructuredAnalysisParser(new ObjectMapper(),
                new AnalysisOutputSchemaValidator()), model, new CopilotProperties.Budget(32000, 2000, 1));

        assertThatThrownBy(() -> orchestrator.execute(ANALYSIS_ID, snapshot()))
                .isInstanceOfSatisfying(ModelInvocationException.class, exception ->
                        assertThat(exception.errorCode()).isEqualTo(com.finguard.copilot.common.error.ErrorCode.FACT_VALIDATION_FAILED));
    }

    @Test
    void injectsPublishedRetrievedEvidenceIntoTheModelContext() throws Exception {
        FixedEmbeddingGateway embedding = new FixedEmbeddingGateway(4);
        InMemoryVectorStore vectors = new InMemoryVectorStore();
        String excerpt = "High amount transactions require enhanced review.";
        KnowledgeChunk chunk = KnowledgeChunk.create("policy-high-amount", "v1", 0, excerpt, "threshold", Map.of());
        vectors.upsert("published-v1", List.of(new VectorDocument(
                chunk.chunkId(), "published-v1", excerpt, embedding.embed(excerpt, "fixed-v1"), Map.of(
                        "documentId", chunk.documentId(), "documentVersion", chunk.documentVersion(),
                        "chunkId", chunk.chunkId(), "sectionPath", chunk.sectionPath(),
                        "contentChecksum", chunk.contentChecksum()))));
        KnowledgeRetrievalService retrieval = new KnowledgeRetrievalService(
                new PublishedRepository(), embedding, vectors, "fixed-v1", 3, -1.0);
        AtomicReference<ModelRequest> request = new AtomicReference<>();
        ModelGateway gateway = modelRequest -> {
            request.set(modelRequest);
            AnalysisResult result = new AnalysisResult(
                    AnalysisResult.CURRENT_SCHEMA_VERSION, ANALYSIS_ID, "summary", "explanation",
                    List.of(new AnalysisResult.BusinessEvidence(11, 42, new BigDecimal("12500.00"),
                            "USD", "RULE_HIGH_AMOUNT", "REASON_AMOUNT_THRESHOLD", List.of("transaction.amount"))),
                    List.of(new AnalysisResult.PolicyCitation("policy-high-amount", "v1", "threshold", excerpt)),
                    com.finguard.copilot.analysis.domain.AnalysisRecommendation.INSUFFICIENT_EVIDENCE,
                    "insufficient", List.of(), List.of());
            String json;
            try {
                json = new ObjectMapper().writeValueAsString(result);
            } catch (com.fasterxml.jackson.core.JsonProcessingException exception) {
                throw new AssertionError(exception);
            }
            return new ModelResponse("qwen-plus", json, 120, 25, json,
                    new ModelUsage(120, 25, Duration.ofMillis(42)), Duration.ofMillis(42), "qwen");
        };
        CopilotProperties.Model model = new CopilotProperties.Model(
                "qwen", "qwen-plus", URI.create("https://model.example.internal"),
                new SecretValue("secret-for-test-only"), 32000, 2000, Duration.ofSeconds(20),
                "phase3-2026-08");
        ContextAssembler assembler = new ContextAssembler(
                TokenBudget.phase3Defaults(), TokenEstimator.conservative(), new PromptTemplate("review-analysis-v1"));
        ModelAnalysisOrchestrator orchestrator = new ModelAnalysisOrchestrator(
                gateway, assembler, new StructuredAnalysisParser(new ObjectMapper(), new AnalysisOutputSchemaValidator()),
                model, new CopilotProperties.Budget(32000, 2000, 1), retrieval, 3);

        orchestrator.execute(ANALYSIS_ID, snapshot());

        assertThat(request.get().context()).contains(excerpt, "REVIEWER_INPUT");
        String renderedKnowledge = request.get().context().split("KNOWLEDGE_EVIDENCE\\n", 2)[1]
                .split("\\nREVIEWER_INPUT", 2)[0];
        try {
            assertThat(new ObjectMapper().readTree(renderedKnowledge).get(0)
                    .path("citation").path("documentId").asText()).isEqualTo("policy-high-amount");
        } catch (com.fasterxml.jackson.core.JsonProcessingException exception) {
            throw new AssertionError(exception);
        }
    }

    private FactSnapshot snapshot() {
        ReviewTaskContext facts = new ReviewTaskContext(
                new ReviewTaskContext.ReviewTask(7, 3, "OPEN", "RULE_HIGH_AMOUNT",
                        "REASON_AMOUNT_THRESHOLD", CAPTURED_AT),
                new ReviewTaskContext.Transaction(11, 42, new BigDecimal("12500.00"), "USD",
                        CAPTURED_AT, "BANK"),
                new ReviewTaskContext.Reconciliation("MATCHED", "REFERENCE_ID", "REASON_AMOUNT_THRESHOLD"),
                List.of(new ReviewTaskContext.RiskFact("HIGH_AMOUNT", "12500.00", "CORE")),
                new ReviewTaskContext.AccountSummary("ACTIVE", "masked-account"), CAPTURED_AT);
        return new FactSnapshot(FactSnapshot.CURRENT_SCHEMA_VERSION, facts, CAPTURED_AT,
                CAPTURED_AT.plus(Duration.ofDays(30)), new byte[FactSnapshot.SHA_256_LENGTH]);
    }

    private String validOutput() throws Exception {
        AnalysisResult result = new AnalysisResult(
                AnalysisResult.CURRENT_SCHEMA_VERSION,
                ANALYSIS_ID,
                "summary",
                "explanation",
                List.of(new AnalysisResult.BusinessEvidence(11, 42, new BigDecimal("12500.00"),
                        "USD", "RULE_HIGH_AMOUNT", "REASON_AMOUNT_THRESHOLD", List.of("transaction.amount"))),
                List.of(),
                com.finguard.copilot.analysis.domain.AnalysisRecommendation.INSUFFICIENT_EVIDENCE,
                "insufficient",
                List.of("policy"),
                List.of("no published policy"));
        return new ObjectMapper().writeValueAsString(result);
    }

    private String validDirectionalOutput() throws Exception {
        AnalysisResult result = new AnalysisResult(
                AnalysisResult.CURRENT_SCHEMA_VERSION,
                ANALYSIS_ID,
                "summary",
                "explanation",
                List.of(new AnalysisResult.BusinessEvidence(11, 42, new BigDecimal("12500.00"),
                        "USD", "RULE_HIGH_AMOUNT", "REASON_AMOUNT_THRESHOLD", List.of("transaction.amount"))),
                List.of(new AnalysisResult.PolicyCitation("fake-policy", "v1", "thresholds", "fake")),
                com.finguard.copilot.analysis.domain.AnalysisRecommendation.CONFIRMED,
                "confirmed",
                List.of(),
                List.of());
        return new ObjectMapper().writeValueAsString(result);
    }

    private static final class PublishedRepository implements KnowledgeIndexRepository {
        @Override
        public void save(KnowledgeIndexVersion version) {
        }

        @Override
        public Optional<KnowledgeIndexVersion> find(String indexVersion) {
            return Optional.of(new KnowledgeIndexVersion("published-v1", KnowledgeIndexStatus.PUBLISHED,
                    CAPTURED_AT, CAPTURED_AT, null));
        }

        @Override
        public Optional<KnowledgeIndexVersion> findPublished() {
            return find("published-v1");
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
}
