package com.finguard.copilot.context;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.finguard.copilot.common.error.CopilotException;
import com.finguard.copilot.common.error.ErrorCode;
import com.finguard.copilot.coreclient.port.ReviewTaskContext;
import com.finguard.copilot.knowledge.port.EmbeddingVector;
import com.finguard.copilot.knowledge.port.VectorDocument;
import com.finguard.copilot.knowledge.port.VectorSearchResult;
import com.finguard.copilot.knowledge.domain.KnowledgeCitation;
import com.finguard.copilot.knowledge.retrieval.KnowledgeEvidence;

class ContextAssemblerTest {

    private static final Instant CAPTURED_AT = Instant.parse("2026-08-31T06:00:00Z");

    private final ContextAssembler assembler = new ContextAssembler(
            TokenBudget.phase3Defaults(),
            TokenEstimator.conservative(),
            new PromptTemplate("review-analysis-v1"));

    @Test
    void assemblesStableSectionsWithinThe32kInputBudgetAndPreservesFacts() {
        ContextAssembler.AssembledContext context = assembler.assemble(
                facts(List.of(new ReviewTaskContext.RiskFact("HIGH_AMOUNT", "12500.00", "CORE"))),
                List.of(result("doc-b", "v1", "Payments above threshold require enhanced review.", 0.8)),
                "Please explain the recommendation.");

        assertThat(context.sections()).containsKeys("system", "facts", "knowledge", "reviewerInput", "reserve");
        assertThat(context.estimatedInputTokens()).isLessThanOrEqualTo(32_000);
        assertThat(context.sections().get("facts"))
                .contains("12500.00", "USD", "RULE_HIGH_AMOUNT", "REASON_AMOUNT_THRESHOLD", "MATCHED");
        assertThat(context.retainedDocumentIds()).containsExactly("doc-b");
        assertThat(context.promptVersion()).isEqualTo("review-analysis-v1");
        assertThat(context.inputFactsVersion()).isNotBlank();
        assertThat(context.knowledgeIndexVersion()).isEqualTo("published-v1");
    }

    @Test
    void deduplicatesKnowledgeByChecksumAndNormalizedText() {
        VectorSearchResult first = result("doc-b", "v1", "Same policy text", 0.8);
        VectorSearchResult duplicate = resultWithChecksum("doc-a", "v1", "  Same   policy text ", 0.9,
                first.document().metadata().get("contentChecksum"));

        ContextAssembler.AssembledContext context = assembler.assemble(
                facts(List.of()), List.of(first, duplicate), "review");

        assertThat(context.retainedDocumentIds()).containsExactly("doc-a");
        assertThat(context.sections().get("knowledge")).contains("doc-a").doesNotContain("doc-b");
    }

    @Test
    void trimsLowestScoringKnowledgeBeforeFactsAreAffected() {
        List<VectorSearchResult> results = List.of(
                result("high", "v1", "H".repeat(45_000), 0.95),
                result("low", "v1", "L".repeat(45_000), 0.10));

        ContextAssembler.AssembledContext context = assembler.assemble(
                facts(List.of(new ReviewTaskContext.RiskFact("HIGH_AMOUNT", "12500.00", "CORE"))),
                results,
                "review");

        assertThat(context.retainedDocumentIds()).containsExactly("high");
        assertThat(context.trimmedDocumentIds()).containsExactly("low");
        assertThat(context.sections().get("facts")).contains("12500.00");
    }

    @Test
    void assemblesRetrievedEvidenceAndKeepsItsCitationProvenance() {
        String excerpt = "Published policy excerpt.";
        KnowledgeCitation citation = new KnowledgeCitation(
                "policy", "v1", "policy:v1:0", "threshold", sha256(excerpt), 0.91, "published-v1");

        ContextAssembler.AssembledContext context = assembler.assembleRetrieved(
                facts(List.of()), List.of(new KnowledgeEvidence(citation, excerpt)), "review");

        assertThat(context.sections().get("knowledge")).contains(excerpt, citation.contentChecksum());
        assertThat(context.retainedCitations()).containsExactly(citation);
        assertThat(context.retainedEvidence()).containsExactly(new KnowledgeEvidence(citation, excerpt));
    }

    @Test
    void rendersCopyableCitationObjectsWithoutChangingMultilineExcerpts() throws Exception {
        String excerpt = "# Review\nUse the \"observed amount\" from Core.";
        KnowledgeCitation citation = new KnowledgeCitation("policy", "v1", "policy:v1:0",
                "# Review", sha256(excerpt), 0.91, "published-v1");
        var context = assembler.assembleRetrieved(facts(List.of()),
                List.of(new KnowledgeEvidence(citation, excerpt)), "review");
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        var rendered = mapper.readTree(context.sections().get("knowledge"));
        var copyable = mapper.treeToValue(rendered.get(0).get("citation"),
                com.finguard.copilot.analysis.domain.AnalysisResult.PolicyCitation.class);
        assertThat(copyable).isEqualTo(new com.finguard.copilot.analysis.domain.AnalysisResult.PolicyCitation(
                "policy", "v1", "# Review", excerpt));
    }

    @Test
    void rejectsFactsThatExceedTheirDedicatedBudget() {
        List<ReviewTaskContext.RiskFact> oversizedFacts = java.util.stream.IntStream.range(0, 2_000)
                .mapToObj(index -> new ReviewTaskContext.RiskFact("FACT_" + index, "value-" + "x".repeat(20), "CORE"))
                .toList();

        assertThatThrownBy(() -> assembler.assemble(facts(oversizedFacts), List.of(), "review"))
                .isInstanceOfSatisfying(CopilotException.class, exception ->
                        assertThat(exception.code()).isEqualTo(ErrorCode.CONTEXT_BUDGET_EXCEEDED));
    }

    @Test
    void rejectsReviewerInputThatExceedsItsDedicatedBudgetInsteadOfSilentlyTruncating() {
        assertThatThrownBy(() -> assembler.assemble(facts(List.of()), List.of(), "x".repeat(10_000)))
                .isInstanceOfSatisfying(CopilotException.class, exception ->
                        assertThat(exception.code()).isEqualTo(ErrorCode.CONTEXT_BUDGET_EXCEEDED));
    }

    @Test
    void conservativeEstimatorIsDeterministicAndReturnsZeroForEmptyText() {
        TokenEstimator estimator = TokenEstimator.conservative();

        assertThat(estimator.estimate("")).isZero();
        assertThat(estimator.estimate("账户金额")).isEqualTo(estimator.estimate("账户金额"));
        assertThat(estimator.estimate("账户金额")).isGreaterThan(0);
        assertThat(estimator.estimate("账户金额".repeat(100))).isEqualTo(400);
    }

    private ReviewTaskContext facts(List<ReviewTaskContext.RiskFact> riskFacts) {
        return new ReviewTaskContext(
                new ReviewTaskContext.ReviewTask(7, 3, "OPEN", "RULE_HIGH_AMOUNT", "REASON_AMOUNT_THRESHOLD", CAPTURED_AT),
                new ReviewTaskContext.Transaction(11, 42, new BigDecimal("12500.00"), "USD", CAPTURED_AT, "BANK"),
                new ReviewTaskContext.Reconciliation("MATCHED", "REFERENCE_ID", "REASON_AMOUNT_THRESHOLD"),
                riskFacts,
                new ReviewTaskContext.AccountSummary("ACTIVE", "masked-account"),
                CAPTURED_AT);
    }

    private VectorSearchResult result(String documentId, String version, String content, double score) {
        return resultWithChecksum(documentId, version, content, score, null);
    }

    private VectorSearchResult resultWithChecksum(
            String documentId, String version, String content, double score, String checksumOverride) {
        String checksum = checksumOverride == null
                ? sha256(content)
                : checksumOverride;
        return new VectorSearchResult(new VectorDocument(
                documentId + ":" + version + ":0", "published-v1", content,
                new EmbeddingVector(List.of(1.0, 0.0)),
                Map.of("documentId", documentId, "documentVersion", version, "chunkId", documentId + ":0",
                        "sectionPath", "policy", "source", "test", "contentChecksum", checksum)), score);
    }

    private String sha256(String content) {
        try {
            return java.util.HexFormat.of().formatHex(
                    java.security.MessageDigest.getInstance("SHA-256")
                            .digest(content.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new AssertionError(exception);
        }
    }
}
