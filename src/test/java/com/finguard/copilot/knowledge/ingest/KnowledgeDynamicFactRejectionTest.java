package com.finguard.copilot.knowledge.ingest;

import com.finguard.copilot.knowledge.domain.KnowledgeSourceType;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class KnowledgeDynamicFactRejectionTest {

    private final KnowledgeIngestionService service = new KnowledgeIngestionService(
            new DocumentCleaner(), new DocumentChunker(200, 10), new KnowledgeAdmissionPolicy(),
            new NoopRepository(), java.time.Instant::now);

    @Test
    void rejectsDynamicCoreFactSourceType() {
        assertThatThrownBy(() -> service.register(new KnowledgeDocumentRequest(
                "fact-1", KnowledgeSourceType.DYNAMIC_CORE_FACT, "live task", "v1",
                "transactionId: 123 amount: 100 reviewStatus: PENDING")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sourceType");
    }

    @Test
    void rejectsSecretsTokensUrlsAndUnsanitizedCaseFields() {
        for (String content : new String[]{
                "Authorization: Bearer eyJhbGciOiJIUzI1NiJ9.abc.def",
                "password = very-secret-value",
                "Read https://example.com/runbook",
                "transactionId: 123 amount: 100 reviewStatus: PENDING"
        }) {
            assertThatThrownBy(() -> service.register(new KnowledgeDocumentRequest(
                    "doc-1", KnowledgeSourceType.POLICY, "policy", "v1", content)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("knowledge admission");
        }
    }

    private static final class NoopRepository implements com.finguard.copilot.knowledge.persistence.KnowledgeDocumentRepository {
        @Override
        public java.util.Optional<com.finguard.copilot.knowledge.domain.KnowledgeDocument> findByCanonicalChecksum(
                String documentId, String sourceVersion, String canonicalChecksum) {
            return java.util.Optional.empty();
        }

        @Override
        public void save(com.finguard.copilot.knowledge.domain.KnowledgeDocument document,
                         java.util.List<com.finguard.copilot.knowledge.domain.KnowledgeChunk> chunks) {
        }
    }
}
