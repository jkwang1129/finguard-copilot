package com.finguard.copilot.guardrail;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.finguard.copilot.analysis.domain.AnalysisRecommendation;
import com.finguard.copilot.analysis.domain.AnalysisResult;
import com.finguard.copilot.coreclient.port.ReviewTaskContext;
import com.finguard.copilot.knowledge.domain.KnowledgeCitation;
import com.finguard.copilot.knowledge.retrieval.KnowledgeEvidence;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThat;

class CoreLargeAmountPolicyGuardTest {
    private static final String POLICY = readPolicy();
    private static final Instant NOW = Instant.parse("2026-10-27T00:00:00Z");
    private final CoreLargeAmountPolicyGuard guard = new CoreLargeAmountPolicyGuard();

    @Test
    void requiresExactPublishedPolicyAndConfirmedRecommendation() {
        assertThatCode(() -> guard.validate(context(), result(AnalysisRecommendation.CONFIRMED, POLICY), List.of(evidence(POLICY,
                CoreLargeAmountPolicyGuard.CONTENT_SHA256)))).doesNotThrowAnyException();
        reject(result(AnalysisRecommendation.IGNORED, POLICY), List.of(evidence(POLICY, CoreLargeAmountPolicyGuard.CONTENT_SHA256)));
        reject(result(AnalysisRecommendation.CONFIRMED, POLICY), List.of());
        reject(result(AnalysisRecommendation.CONFIRMED, "altered"), List.of(evidence(POLICY, CoreLargeAmountPolicyGuard.CONTENT_SHA256)));
        reject(result(AnalysisRecommendation.CONFIRMED, POLICY), List.of(evidence(POLICY + " altered", CoreLargeAmountPolicyGuard.CONTENT_SHA256)));
        reject(result(AnalysisRecommendation.CONFIRMED, POLICY), List.of(evidence(POLICY, "wrong")));
        reject(result(AnalysisRecommendation.CONFIRMED, POLICY), List.of(evidence(POLICY, CoreLargeAmountPolicyGuard.CONTENT_SHA256),
                evidence(POLICY, CoreLargeAmountPolicyGuard.CONTENT_SHA256)));
    }

    @Test
    void allowsInsufficientEvidenceWithoutPolicy() {
        assertThatCode(() -> guard.validate(context(), result(AnalysisRecommendation.INSUFFICIENT_EVIDENCE, POLICY), List.of()))
                .doesNotThrowAnyException();
    }

    @Test
    void approvedPolicyPassesAdmissionAndProducesThePinnedChunk() {
        var request = new com.finguard.copilot.knowledge.ingest.KnowledgeDocumentRequest(
                CoreLargeAmountPolicyGuard.DOCUMENT_ID, com.finguard.copilot.knowledge.domain.KnowledgeSourceType.POLICY,
                "Project large amount review policy", "v1", POLICY);
        var cleaned = new com.finguard.copilot.knowledge.ingest.DocumentCleaner().canonicalize(POLICY)
                .withIdentity(CoreLargeAmountPolicyGuard.DOCUMENT_ID, "v1");
        new com.finguard.copilot.knowledge.ingest.KnowledgeAdmissionPolicy().check(request, cleaned);
        var chunks = new com.finguard.copilot.knowledge.ingest.DocumentChunker(2048, 0).chunk(cleaned);
        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0).content()).isEqualTo(POLICY);
        assertThat(chunks.get(0).sectionPath()).isEqualTo("Document");
        assertThat(chunks.get(0).contentChecksum()).isEqualTo(CoreLargeAmountPolicyGuard.CONTENT_SHA256);
    }

    private void reject(AnalysisResult result, List<KnowledgeEvidence> evidence) {
        assertThatThrownBy(() -> guard.validate(context(), result, evidence))
                .isInstanceOf(com.finguard.copilot.common.error.CopilotException.class);
    }

    private AnalysisResult result(AnalysisRecommendation recommendation, String excerpt) {
        return new AnalysisResult(AnalysisResult.CURRENT_SCHEMA_VERSION, UUID.randomUUID(), "summary", "explanation",
                List.of(new AnalysisResult.BusinessEvidence(2, 3, new BigDecimal("10000.00"), "AUD",
                        "LARGE_AMOUNT", "AMOUNT_AT_OR_ABOVE_THRESHOLD", List.of("transaction.amount"))),
                List.of(new AnalysisResult.PolicyCitation(CoreLargeAmountPolicyGuard.DOCUMENT_ID,
                "v1", "Document", excerpt)), recommendation, "reason", List.of(), List.of());
    }

    private KnowledgeEvidence evidence(String content, String hash) {
        return new KnowledgeEvidence(new KnowledgeCitation(CoreLargeAmountPolicyGuard.DOCUMENT_ID, "v1", "chunk",
                "Document", hash, 0.9, "index-v1"), content);
    }

    private ReviewTaskContext context() {
        return new ReviewTaskContext(new ReviewTaskContext.ReviewTask(1, 0, "PENDING", "LARGE_AMOUNT",
                "AMOUNT_AT_OR_ABOVE_THRESHOLD", NOW),
                new ReviewTaskContext.Transaction(2, 3, new BigDecimal("10000.00"), "AUD", NOW, "CSV_IMPORT"),
                new ReviewTaskContext.Reconciliation("MATCHED", "REFERENCE_ID", "REASON"), List.of(),
                new ReviewTaskContext.AccountSummary("ACTIVE", "masked"), NOW);
    }

    private static String readPolicy() {
        try {
            return Files.readString(Path.of("src/main/resources/knowledge/large-amount-review-policy-v1.txt")).trim();
        } catch (java.io.IOException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
