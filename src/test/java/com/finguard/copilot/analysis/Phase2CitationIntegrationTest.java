package com.finguard.copilot.analysis;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.finguard.copilot.analysis.application.DeterministicAnalysisOrchestrator;
import com.finguard.copilot.analysis.domain.AnalysisRecommendation;
import com.finguard.copilot.analysis.domain.AnalysisResult;
import com.finguard.copilot.context.FactSnapshot;
import com.finguard.copilot.context.SnapshotCodec;
import com.finguard.copilot.coreclient.port.ReviewTaskContext;
import com.finguard.copilot.foundation.stub.FixedEmbeddingGateway;
import com.finguard.copilot.foundation.stub.InMemoryVectorStore;
import com.finguard.copilot.guardrail.AnalysisOutputSchemaValidator;
import com.finguard.copilot.guardrail.FactConsistencyValidator;
import com.finguard.copilot.knowledge.domain.KnowledgeChunk;
import com.finguard.copilot.knowledge.domain.KnowledgeIndexStatus;
import com.finguard.copilot.knowledge.domain.KnowledgeIndexVersion;
import com.finguard.copilot.knowledge.persistence.KnowledgeIndexRepository;
import com.finguard.copilot.knowledge.port.VectorDocument;
import com.finguard.copilot.knowledge.retrieval.KnowledgeRetrievalService;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class Phase2CitationIntegrationTest {

    private static final UUID ANALYSIS_ID = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
    private static final Instant CAPTURED_AT = Instant.parse("2026-08-25T01:02:03.456789Z");

    @Test
    void attachesOnlyPublishedEvidenceAndStillWithholdsDirectionalRecommendation() {
        FixedEmbeddingGateway embedding = new FixedEmbeddingGateway(4);
        InMemoryVectorStore vectors = new InMemoryVectorStore();
        vectors.createIndex("published", 4);
        KnowledgeChunk chunk = KnowledgeChunk.create(
                "policy-amount", "v1", 0, "# High amount\n\nAmount threshold requires review.",
                "High amount", Map.of());
        vectors.upsert("published", List.of(new VectorDocument(
                chunk.chunkId(), "published", chunk.content(),
                embedding.embed(chunk.content(), "embedding-v1"),
                Map.of("documentId", chunk.documentId(), "documentVersion", chunk.documentVersion(),
                        "chunkId", chunk.chunkId(), "sectionPath", chunk.sectionPath(),
                        "contentChecksum", chunk.contentChecksum()))));

        KnowledgeRetrievalService retrieval = new KnowledgeRetrievalService(
                new PublishedRepository(), embedding, vectors, "embedding-v1", 3, -1.0);
        DeterministicAnalysisOrchestrator orchestrator = new DeterministicAnalysisOrchestrator(
                new AnalysisOutputSchemaValidator(new ObjectMapper()), new FactConsistencyValidator(), retrieval);

        AnalysisResult result = orchestrator.analyze(ANALYSIS_ID, snapshot());

        assertThat(result.policyCitations()).singleElement().satisfies(citation -> {
            assertThat(citation.documentId()).isEqualTo("policy-amount");
            assertThat(citation.version()).isEqualTo("v1");
            assertThat(citation.sourcePath()).isEqualTo("High amount");
        });
        assertThat(result.recommendation()).isEqualTo(AnalysisRecommendation.INSUFFICIENT_EVIDENCE);
        assertThat(result.missingInformation()).isEmpty();
    }

    private FactSnapshot snapshot() {
        return new SnapshotCodec().capture(new ReviewTaskContext(
                new ReviewTaskContext.ReviewTask(101, 0, "PENDING", "HIGH_AMOUNT",
                        "AMOUNT_THRESHOLD_EXCEEDED", CAPTURED_AT.minusSeconds(60)),
                new ReviewTaskContext.Transaction(202, 303, new BigDecimal("120000.00"), "CNY",
                        CAPTURED_AT.minusSeconds(30), "CSV_IMPORT"),
                new ReviewTaskContext.Reconciliation("SUSPICIOUS", "AMOUNT_AND_TIME", "REQUIRES_REVIEW"),
                List.of(new ReviewTaskContext.RiskFact("HIGH_AMOUNT", "120000.00", "TRANSACTION")),
                new ReviewTaskContext.AccountSummary("ACTIVE", "Primary"), CAPTURED_AT));
    }

    private static final class PublishedRepository implements KnowledgeIndexRepository {
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
        public void markEvaluated(String indexVersion,
                                  com.finguard.copilot.knowledge.index.RagEvaluationResult result) {
        }

        @Override
        public void markFailed(String indexVersion) {
        }

        @Override
        public void compareAndPublish(String candidateVersion, String expectedCurrentVersion) {
        }
    }
}
