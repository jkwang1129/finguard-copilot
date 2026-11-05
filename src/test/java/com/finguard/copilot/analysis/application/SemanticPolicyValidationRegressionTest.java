package com.finguard.copilot.analysis.application;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finguard.copilot.analysis.domain.*;
import com.finguard.copilot.config.CopilotProperties;
import com.finguard.copilot.context.*;
import com.finguard.copilot.coreclient.port.ReviewTaskContext;
import com.finguard.copilot.guardrail.*;
import com.finguard.copilot.knowledge.domain.*;
import com.finguard.copilot.knowledge.retrieval.*;
import com.finguard.copilot.model.port.*;
import com.finguard.copilot.model.validation.StructuredAnalysisParser;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Counterfactual local candidates, never a replay of the unretained provider outputs. */
class SemanticPolicyValidationRegressionTest {
    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();

    static Stream<JsonNode> directionalCases() throws Exception {
        try (var lines = Files.lines(Path.of("tools/agent-evaluation/data/semantic-business-v2/cases.jsonl"))) {
            return lines.map(line -> {
                try { return JSON.readTree(line); }
                catch (java.io.IOException exception) { throw new java.io.UncheckedIOException(exception); }
            }).filter(row -> !"INSUFFICIENT_EVIDENCE".equals(row.path("expectedRecommendation").asText()))
                    .toList().stream();
        }
    }

    @ParameterizedTest
    @MethodSource("directionalCases")
    void correctLocalCandidateCanPassTheActualP4PolicyValidation(JsonNode row) throws Exception {
        String policy;
        try (var lines = Files.lines(Path.of("tools/agent-evaluation/data/semantic-quality-v1/corpus.jsonl"))) {
            policy = lines.map(line -> {
                try { return JSON.readTree(line); }
                catch (java.io.IOException exception) { throw new java.io.UncheckedIOException(exception); }
            }).filter(doc -> "synth-usd-limit".equals(doc.path("id").asText()))
                    .findFirst().orElseThrow().path("content").asText();
        }
        var chunk = KnowledgeChunk.create("synth-usd-limit", "v1", 0, policy, "Document", Map.of());
        var evidence = new KnowledgeEvidence(new KnowledgeCitation(chunk.documentId(), chunk.documentVersion(),
                chunk.chunkId(), chunk.sectionPath(), chunk.contentChecksum(), 1, "offline-regression"), policy);
        var binding = new ReviewedPolicyGuard.Binding(chunk.documentId(), chunk.documentVersion(),
                chunk.sectionPath(), chunk.contentChecksum());
        var reviewed = new ReviewedPolicyGuard(List.of(new ReviewedPolicyGuard.PolicySet("synthetic-project-usd-v1",
                "SYNTH_LIMIT", "USD", List.of(new ReviewedPolicyGuard.Policy(binding, List.of(
                new ReviewedPolicyGuard.Condition(ReviewedPolicyGuard.Field.AMOUNT, ReviewedPolicyGuard.Operator.GTE, "100.00"),
                new ReviewedPolicyGuard.Condition(ReviewedPolicyGuard.Field.CURRENCY, ReviewedPolicyGuard.Operator.EQ, "USD")),
                AnalysisRecommendation.CONFIRMED, AnalysisRecommendation.IGNORED)))));
        var now = Instant.parse("2026-11-04T00:00:00Z");
        var amount = new BigDecimal(row.path("amount").asText());
        var facts = new ReviewTaskContext(new ReviewTaskContext.ReviewTask(42, 0, "PENDING", "SYNTH_LIMIT",
                row.path("reason_code").asText(), now), new ReviewTaskContext.Transaction(2, 3, amount, "USD", now, "CSV_IMPORT"),
                new ReviewTaskContext.Reconciliation("SUSPICIOUS", "NONE", "REVIEW"), List.of(),
                new ReviewTaskContext.AccountSummary("ACTIVE", row.path("account").asText()), now);
        var snapshot = new SnapshotCodec().capture(facts);
        var id = UUID.nameUUIDFromBytes(row.path("case_id").asText().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        var expected = AnalysisRecommendation.valueOf(row.path("expectedRecommendation").asText());
        var candidate = new AnalysisResult(1, id, "Local counterfactual candidate", "Project synthetic policy",
                List.of(new AnalysisResult.BusinessEvidence(2, 3, amount, "USD", "SYNTH_LIMIT",
                        row.path("reason_code").asText(), List.of("transaction.amount"))),
                List.of(new AnalysisResult.PolicyCitation(chunk.documentId(), "v1", "Document", policy)),
                expected, "Deterministic test fixture, not model output", List.of(), List.of());
        String output = JSON.writeValueAsString(candidate);
        ModelGateway local = ignored -> new ModelResponse("stub", output, 1, 1);
        var model = new CopilotProperties.Model("stub", "stub", java.net.URI.create("https://example.invalid"),
                com.finguard.copilot.config.SecretValue.empty(), 32000, 2000, java.time.Duration.ofSeconds(20), "offline-test");
        var retrieval = mock(KnowledgeRetrievalService.class);
        when(retrieval.retrieve(anyString(), eq(1))).thenReturn(List.of(evidence));
        var orchestrator = new ModelAnalysisOrchestrator(local,
                new ContextAssembler(TokenBudget.phase3Defaults(), TokenEstimator.conservative(), new PromptTemplate("review-analysis-v2")),
                new StructuredAnalysisParser(), model, CopilotProperties.Budget.defaults(),
                retrieval, 1, new NumericThresholdGuard(List.of()), reviewed);
        var result = new java.util.concurrent.atomic.AtomicReference<AnalysisResult>();
        assertThatCode(() -> result.set(orchestrator.execute(id, snapshot).result())).doesNotThrowAnyException();
        var actual = result.get();
        new FactConsistencyValidator().validate(id, snapshot, actual);
        assertThat(actual.recommendation()).isEqualTo(expected);
        assertThat(actual.businessEvidence().get(0).amount()).isEqualByComparingTo(amount);
    }
}
