package com.finguard.copilot.knowledge.ingest;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.finguard.copilot.knowledge.domain.KnowledgeChunk;
import com.finguard.copilot.knowledge.domain.KnowledgeDocument;
import com.finguard.copilot.knowledge.domain.KnowledgeSourceType;
import com.finguard.copilot.knowledge.persistence.KnowledgeDocumentRepository;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class KnowledgeIngestionServiceTest {

    @Test
    void registersDocumentAndChunksOnceAndReturnsExistingVersionOnDuplicate() {
        InMemoryRepository repository = new InMemoryRepository();
        KnowledgeIngestionService service = new KnowledgeIngestionService(
                new DocumentCleaner(), new DocumentChunker(80, 8), new KnowledgeAdmissionPolicy(), repository,
                Instant::now);
        KnowledgeDocumentRequest request = new KnowledgeDocumentRequest(
                "policy-1", KnowledgeSourceType.POLICY, "Risk policy", "v1",
                "# Risk\n\nEscalate high amount reviews.");

        KnowledgeDocument first = service.register(request);
        KnowledgeDocument second = service.register(request);

        assertThat(second).isEqualTo(first);
        assertThat(repository.savedDocuments).hasSize(1);
        assertThat(repository.savedChunks).hasSize(1);
        assertThat(repository.savedChunks.get(0).chunkId()).isEqualTo("policy-1:v1:0");
    }

    private static final class InMemoryRepository implements KnowledgeDocumentRepository {
        private final List<KnowledgeDocument> savedDocuments = new ArrayList<>();
        private final List<KnowledgeChunk> savedChunks = new ArrayList<>();

        @Override
        public Optional<KnowledgeDocument> findByCanonicalChecksum(
                String documentId, String sourceVersion, String canonicalChecksum) {
            return savedDocuments.stream()
                    .filter(document -> document.documentId().equals(documentId))
                    .filter(document -> document.sourceVersion().equals(sourceVersion))
                    .filter(document -> document.canonicalChecksum().equals(canonicalChecksum))
                    .findFirst();
        }

        @Override
        public void save(KnowledgeDocument document, List<KnowledgeChunk> chunks) {
            savedDocuments.add(document);
            savedChunks.addAll(chunks);
        }
    }
}
