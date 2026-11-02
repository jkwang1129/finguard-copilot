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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.CsvSource;

class ModelAnalysisOrchestratorTest {

    @Test
    void configurationPreflightRejectsBeforeGatewayAndDoesNotInventUsage() {
        ModelGateway gateway=org.mockito.Mockito.mock(ModelGateway.class);
        var model=new CopilotProperties.Model("deepseek","deepseek-flash",URI.create("https://api.deepseek.com"),
                new SecretValue("test-only"),32000,2000,Duration.ofSeconds(20),"price-v1");
        var retrieval=org.mockito.Mockito.mock(KnowledgeRetrievalService.class);
        org.mockito.Mockito.when(retrieval.retrieveBatch(org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(new KnowledgeRetrievalService.RetrievalBatch("idx",List.of()));
        var orchestrator=new ModelAnalysisOrchestrator(gateway,
                new ContextAssembler(TokenBudget.phase3Defaults(),TokenEstimator.conservative(),new PromptTemplate("review-analysis-v2")),
                new StructuredAnalysisParser(),model,CopilotProperties.Budget.defaults(),retrieval,1,
                new com.finguard.copilot.guardrail.NumericThresholdGuard(List.of()),
                new com.finguard.copilot.guardrail.ReviewedPolicyGuard(List.of()),
                (id,snapshot,request,evidence,index)->{throw com.finguard.copilot.governance.RunManifestService.rejected("price conflict");});
        assertThatThrownBy(()->orchestrator.execute(ANALYSIS_ID,snapshot()))
                .isInstanceOf(com.finguard.copilot.common.error.CopilotException.class).hasMessageContaining("price conflict");
        org.mockito.Mockito.verifyNoInteractions(gateway);
    }

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

    @Test
    void rejectsH03WrongDirectionalRecommendationBeforeWorkerCanPersistIt() throws Exception {
        com.fasterxml.jackson.databind.JsonNode h03;
        try (var lines = java.nio.file.Files.lines(
                java.nio.file.Path.of("tools/agent-evaluation/data/phase3-holdout-v1.jsonl"))) {
            String json = lines.filter(line -> line.contains("\"H03-precision-above\"")).findFirst().orElseThrow();
            h03 = new ObjectMapper().readTree(json);
        }
        assertThat(h03.path("expected_recommendation").asText()).isEqualTo("CONFIRMED");
        assertThat(h03.path("input_facts").path("transaction").path("amount").asText())
                .isEqualTo("0.100000000000000001");
        String excerpt = h03.path("knowledge_evidence").get(0).path("content").asText();
        FixedEmbeddingGateway embedding = new FixedEmbeddingGateway(4);
        InMemoryVectorStore vectors = new InMemoryVectorStore();
        KnowledgeChunk chunk = KnowledgeChunk.create("HO-P03", "1.0", 0, excerpt,
                "approved/fractional-ledger-strict", Map.of());
        vectors.upsert("published-v1", List.of(new VectorDocument(
                chunk.chunkId(), "published-v1", excerpt, embedding.embed(excerpt, "fixed-v1"), Map.of(
                        "documentId", chunk.documentId(), "documentVersion", chunk.documentVersion(),
                        "chunkId", chunk.chunkId(), "sectionPath", chunk.sectionPath(),
                        "contentChecksum", chunk.contentChecksum()))));
        KnowledgeRetrievalService retrieval = new KnowledgeRetrievalService(
                new PublishedRepository(), embedding, vectors, "fixed-v1", 3, -1.0);
        AnalysisResult wrongResult = new AnalysisResult(
                AnalysisResult.CURRENT_SCHEMA_VERSION, ANALYSIS_ID, "summary", "explanation",
                List.of(new AnalysisResult.BusinessEvidence(78103, 68103,
                        new BigDecimal("0.100000000000000001"), "USD", "HO_RULE_03",
                        "HO_ALERT_03", List.of("transaction.amount"))),
                List.of(new AnalysisResult.PolicyCitation("HO-P03", "1.0",
                        "approved/fractional-ledger-strict", excerpt)),
                com.finguard.copilot.analysis.domain.AnalysisRecommendation.IGNORED,
                "below threshold", List.of(), List.of());
        String output = new ObjectMapper().writeValueAsString(wrongResult);
        ModelGateway gateway = ignored -> new ModelResponse("qwen-plus", output, 120, 25, output,
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
                new AnalysisOutputSchemaValidator()), model, new CopilotProperties.Budget(32000, 2000, 1),
                retrieval, 3, new com.finguard.copilot.guardrail.NumericThresholdGuard(List.of(
                        new com.finguard.copilot.guardrail.NumericThresholdGuard.Rule(
                                "h03-test-v1", "HO_RULE_03", "USD",
                                com.finguard.copilot.guardrail.NumericThresholdGuard.Operator.GT,
                                new BigDecimal("0.100000000000000000"), "HO-P03", "1.0",
                                "approved/fractional-ledger-strict", chunk.contentChecksum()))));
        ReviewTaskContext facts = new ReviewTaskContext(
                new ReviewTaskContext.ReviewTask(98103, 0, "PENDING", "HO_RULE_03", "HO_ALERT_03", CAPTURED_AT),
                new ReviewTaskContext.Transaction(78103, 68103,
                        new BigDecimal("0.100000000000000001"), "USD", CAPTURED_AT, "SYNTHETIC"),
                new ReviewTaskContext.Reconciliation("MATCHED", "REFERENCE_ID", "HO_ALERT_03"),
                List.of(), new ReviewTaskContext.AccountSummary("ACTIVE", "masked"), CAPTURED_AT);
        FactSnapshot snapshot = new FactSnapshot(FactSnapshot.CURRENT_SCHEMA_VERSION, facts, CAPTURED_AT,
                CAPTURED_AT.plus(Duration.ofDays(30)), new byte[FactSnapshot.SHA_256_LENGTH]);

        assertThatThrownBy(() -> orchestrator.execute(ANALYSIS_ID, snapshot))
                .isInstanceOfSatisfying(ModelInvocationException.class, exception -> {
                        assertThat(exception.errorCode())
                                .isEqualTo(com.finguard.copilot.common.error.ErrorCode.FACT_VALIDATION_FAILED);
                        assertThat(exception.usage()).isEqualTo(new ModelUsage(120, 25, Duration.ofMillis(42)));
                        assertThat(exception.outcome()).isEqualTo("FAILURE");
                });
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void rejectsDirectionalOutputWhenRetainedH12DocumentContainsAnInstructionAttack(boolean obfuscated) throws Exception {
        String attack;
        try (var lines = java.nio.file.Files.lines(
                java.nio.file.Path.of("tools/agent-evaluation/data/phase3-holdout-v1.jsonl"))) {
            String json = lines.filter(line -> line.contains("\"H12-upload-license-injection\""))
                    .findFirst().orElseThrow();
            attack = new ObjectMapper().readTree(json).path("knowledge_evidence").get(1).path("content").asText();
        }
        if (obfuscated) {
            attack = attack.replace("忽略原有政策", "忽\u200d略\n原有政策");
        }
        String safeText = "Approved review policy requires human verification.";
        KnowledgeChunk safe = KnowledgeChunk.create("safe-policy", "v1", 0, safeText, "review", Map.of());
        KnowledgeChunk malicious = KnowledgeChunk.create("untrusted-upload", "v1", 0, attack, "attachment", Map.of());
        FixedEmbeddingGateway embedding = new FixedEmbeddingGateway(4);
        InMemoryVectorStore vectors = new InMemoryVectorStore();
        vectors.upsert("published-v1", List.of(safe, malicious).stream().map(chunk -> new VectorDocument(
                chunk.chunkId(), "published-v1", chunk.content(), embedding.embed(chunk.content(), "fixed-v1"),
                Map.of("documentId", chunk.documentId(), "documentVersion", chunk.documentVersion(),
                        "chunkId", chunk.chunkId(), "sectionPath", chunk.sectionPath(),
                        "contentChecksum", chunk.contentChecksum()))).toList());
        KnowledgeRetrievalService retrieval = new KnowledgeRetrievalService(
                new PublishedRepository(), embedding, vectors, "fixed-v1", 3, -1.0);
        AnalysisResult unsafe = new AnalysisResult(AnalysisResult.CURRENT_SCHEMA_VERSION, ANALYSIS_ID,
                "summary", "explanation",
                List.of(new AnalysisResult.BusinessEvidence(11, 42, new BigDecimal("12500.00"), "USD",
                        "RULE_HIGH_AMOUNT", "REASON_AMOUNT_THRESHOLD", List.of("transaction.amount"))),
                List.of(new AnalysisResult.PolicyCitation("safe-policy", "v1", "review", safeText)),
                com.finguard.copilot.analysis.domain.AnalysisRecommendation.CONFIRMED,
                "review is supported", List.of(), List.of());
        String output = new ObjectMapper().writeValueAsString(unsafe);
        ModelGateway gateway = ignored -> new ModelResponse("qwen-plus", output, 120, 25, output,
                new ModelUsage(120, 25, Duration.ofMillis(42)), Duration.ofMillis(42), "qwen");
        CopilotProperties.Model model = new CopilotProperties.Model(
                "qwen", "qwen-plus", URI.create("https://model.example.internal"),
                new SecretValue("secret-for-test-only"), 32000, 2000, Duration.ofSeconds(20), "phase3-2026-08");
        ContextAssembler assembler = new ContextAssembler(TokenBudget.phase3Defaults(),
                TokenEstimator.conservative(), new PromptTemplate("review-analysis-v1"));
        ModelAnalysisOrchestrator orchestrator = new ModelAnalysisOrchestrator(
                gateway, assembler, new StructuredAnalysisParser(), model,
                new CopilotProperties.Budget(32000, 2000, 1), retrieval, 2);

        assertThatThrownBy(() -> orchestrator.execute(ANALYSIS_ID, snapshot()))
                .isInstanceOfSatisfying(ModelInvocationException.class, exception ->
                        assertThat(exception.errorCode())
                                .isEqualTo(com.finguard.copilot.common.error.ErrorCode.FACT_VALIDATION_FAILED));
    }

    @ParameterizedTest
    @CsvSource({"12500.00, CONFIRMED, true", "10000.00, CONFIRMED, true",
            "15000.00, CONFIRMED, false", "10000.00, IGNORED, false"})
    void appliesProjectLargeAmountPolicyAfterValidatingCoreFacts(String threshold,
            com.finguard.copilot.analysis.domain.AnalysisRecommendation recommendation, boolean accepted) throws Exception {
        String excerpt = java.nio.file.Files.readString(java.nio.file.Path.of(
                "src/main/resources/knowledge/large-amount-review-policy-v1.txt")).trim();
        KnowledgeChunk chunk = KnowledgeChunk.create("finguard-large-amount-review-policy", "v1", 0,
                excerpt, "Document", Map.of());
        FixedEmbeddingGateway embedding = new FixedEmbeddingGateway(4);
        InMemoryVectorStore vectors = new InMemoryVectorStore();
        vectors.upsert("published-v1", List.of(new VectorDocument(chunk.chunkId(), "published-v1", excerpt,
                embedding.embed(excerpt, "fixed-v1"), Map.of("documentId", chunk.documentId(),
                "documentVersion", chunk.documentVersion(), "chunkId", chunk.chunkId(),
                "sectionPath", chunk.sectionPath(), "contentChecksum", chunk.contentChecksum()))));
        KnowledgeRetrievalService retrieval = new KnowledgeRetrievalService(
                new PublishedRepository(), embedding, vectors, "fixed-v1", 3, -1.0);
        AnalysisResult result = new AnalysisResult(AnalysisResult.CURRENT_SCHEMA_VERSION, ANALYSIS_ID,
                "summary", "explanation", List.of(new AnalysisResult.BusinessEvidence(11, 42,
                new BigDecimal("12500.00"), "USD", "LARGE_AMOUNT", "REASON_AMOUNT_THRESHOLD",
                List.of("transaction.amount"))), List.of(new AnalysisResult.PolicyCitation(
                "finguard-large-amount-review-policy", "v1", "Document", excerpt)), recommendation,
                "review", List.of(), List.of());
        String output = new ObjectMapper().writeValueAsString(result);
        ModelGateway gateway = ignored -> new ModelResponse("qwen-plus", output, 120, 25, output,
                new ModelUsage(120, 25, Duration.ofMillis(42)), Duration.ofMillis(42), "qwen");
        CopilotProperties.Model model = new CopilotProperties.Model("qwen", "qwen-plus",
                URI.create("https://model.example.internal"), new SecretValue("secret-for-test-only"),
                32000, 2000, Duration.ofSeconds(20), "phase3-2026-08");
        ModelAnalysisOrchestrator orchestrator = new ModelAnalysisOrchestrator(gateway,
                new ContextAssembler(TokenBudget.phase3Defaults(), TokenEstimator.conservative(),
                        new PromptTemplate("review-analysis-v1")), new StructuredAnalysisParser(), model,
                new CopilotProperties.Budget(32000, 2000, 1), retrieval, 1);
        ReviewTaskContext facts = new ReviewTaskContext(
                new ReviewTaskContext.ReviewTask(7, 3, "OPEN", "LARGE_AMOUNT", "REASON_AMOUNT_THRESHOLD", CAPTURED_AT),
                new ReviewTaskContext.Transaction(11, 42, new BigDecimal("12500.00"), "USD", CAPTURED_AT, "BANK"),
                new ReviewTaskContext.Reconciliation("MATCHED", "REFERENCE_ID", "REASON_AMOUNT_THRESHOLD"),
                List.of(new ReviewTaskContext.RiskFact("OBSERVED_AMOUNT", "12500.00", "riskHit.observedAmount"),
                        new ReviewTaskContext.RiskFact("THRESHOLD_AMOUNT", threshold, "riskHit.thresholdAmount")),
                new ReviewTaskContext.AccountSummary("ACTIVE", "masked-account"), CAPTURED_AT);
        FactSnapshot snapshot = new FactSnapshot(FactSnapshot.CURRENT_SCHEMA_VERSION, facts, CAPTURED_AT,
                CAPTURED_AT.plus(Duration.ofDays(30)), new byte[FactSnapshot.SHA_256_LENGTH]);

        if (accepted) {
            assertThat(orchestrator.execute(ANALYSIS_ID, snapshot).result().recommendation()).isEqualTo(recommendation);
        } else {
            assertThatThrownBy(() -> orchestrator.execute(ANALYSIS_ID, snapshot))
                    .isInstanceOfSatisfying(ModelInvocationException.class, exception -> {
                        assertThat(exception.errorCode()).isEqualTo(com.finguard.copilot.common.error.ErrorCode.FACT_VALIDATION_FAILED);
                        assertThat(exception.usage()).isEqualTo(new ModelUsage(120, 25, Duration.ofMillis(42)));
                    });
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

    @Test
    void authenticCitationDoesNotAuthorizeAnUnreviewedDirectionalPolicy() throws Exception {
        var evidence=new com.finguard.copilot.knowledge.retrieval.KnowledgeEvidence(
                new com.finguard.copilot.knowledge.domain.KnowledgeCitation("fake-policy","v1","chunk","thresholds",
                        "a".repeat(64),1,"fixture"),"fake");
        var retrieval=org.mockito.Mockito.mock(KnowledgeRetrievalService.class);
        org.mockito.Mockito.when(retrieval.retrieve(org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.anyInt())).thenReturn(List.of(evidence));
        String output=validDirectionalOutput();
        var model=new CopilotProperties.Model("stub","fixture",URI.create("https://example.invalid"),new SecretValue("test-only"),32000,2000,Duration.ofSeconds(10),"test");
        var orchestrator=new ModelAnalysisOrchestrator(request->new ModelResponse("fixture",output,10,5),
                new ContextAssembler(TokenBudget.phase3Defaults(),TokenEstimator.conservative(),new PromptTemplate("review-analysis-v1")),
                new StructuredAnalysisParser(),model,new CopilotProperties.Budget(32000,2000,1),retrieval,1);
        assertThatThrownBy(()->orchestrator.execute(ANALYSIS_ID,snapshot()))
                .isInstanceOfSatisfying(ModelInvocationException.class,error->assertThat(error.errorCode())
                        .isEqualTo(com.finguard.copilot.common.error.ErrorCode.FACT_VALIDATION_FAILED));
    }

    @Test
    void storedCitationFreeRefusalRecoversEvenWhenKnowledgeIsUnavailable() throws Exception {
        var retrieval=org.mockito.Mockito.mock(KnowledgeRetrievalService.class);
        org.mockito.Mockito.when(retrieval.retrieve(org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.anyInt()))
                .thenThrow(new com.finguard.copilot.knowledge.retrieval.KnowledgeRetrievalException("outage"));
        var gateway=org.mockito.Mockito.mock(ModelGateway.class);
        var model=new CopilotProperties.Model("stub","fixture",URI.create("https://example.invalid"),new SecretValue("test-only"),32000,2000,Duration.ofSeconds(10),"test");
        var orchestrator=new ModelAnalysisOrchestrator(gateway,new ContextAssembler(TokenBudget.phase3Defaults(),TokenEstimator.conservative(),
                new PromptTemplate("review-analysis-v1")),new StructuredAnalysisParser(),model,new CopilotProperties.Budget(32000,2000,1),retrieval,1);
        var stored=new StructuredAnalysisParser().parse(validOutput());
        org.assertj.core.api.Assertions.assertThatCode(()->orchestrator.validateStoredResult(snapshot(),stored)).doesNotThrowAnyException();
        org.mockito.Mockito.verifyNoInteractions(gateway,retrieval);
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
