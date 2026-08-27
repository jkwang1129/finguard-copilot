package com.finguard.copilot.knowledge.ingest;

import java.time.Instant;
import java.util.List;
import java.util.function.Supplier;

import com.finguard.copilot.knowledge.domain.KnowledgeChunk;
import com.finguard.copilot.knowledge.domain.KnowledgeDocument;
import com.finguard.copilot.knowledge.persistence.KnowledgeDocumentRepository;

public final class KnowledgeIngestionService {

    private final DocumentCleaner cleaner;
    private final DocumentChunker chunker;
    private final KnowledgeAdmissionPolicy admissionPolicy;
    private final KnowledgeDocumentRepository repository;
    private final Supplier<Instant> clock;

    public KnowledgeIngestionService(
            DocumentCleaner cleaner,
            DocumentChunker chunker,
            KnowledgeAdmissionPolicy admissionPolicy,
            KnowledgeDocumentRepository repository,
            Supplier<Instant> clock
    ) {
        this.cleaner = requireNonNull(cleaner, "cleaner");
        this.chunker = requireNonNull(chunker, "chunker");
        this.admissionPolicy = requireNonNull(admissionPolicy, "admissionPolicy");
        this.repository = requireNonNull(repository, "repository");
        this.clock = requireNonNull(clock, "clock");
    }

    public KnowledgeDocument register(KnowledgeDocumentRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("request must not be null");
        }
        CanonicalDocument canonical = cleaner.canonicalize(request.rawText())
                .withIdentity(request.documentId(), request.sourceVersion());
        admissionPolicy.check(request, canonical);
        return repository.findByCanonicalChecksum(
                        request.documentId(), request.sourceVersion(), canonical.checksum())
                .orElseGet(() -> registerNew(request, canonical));
    }

    private KnowledgeDocument registerNew(KnowledgeDocumentRequest request, CanonicalDocument canonical) {
        KnowledgeDocument document = KnowledgeDocument.register(
                request.documentId(),
                request.sourceType(),
                request.title(),
                request.sourceVersion(),
                canonical.checksum(),
                clock.get());
        List<KnowledgeChunk> chunks = chunker.chunk(canonical);
        repository.save(document, chunks);
        return document;
    }

    private static <T> T requireNonNull(T value, String name) {
        if (value == null) {
            throw new IllegalArgumentException(name + " must not be null");
        }
        return value;
    }
}
