package com.finguard.copilot.knowledge.index;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import com.finguard.copilot.knowledge.domain.KnowledgeChunk;
import com.finguard.copilot.knowledge.domain.KnowledgeDocument;
import com.finguard.copilot.knowledge.domain.KnowledgeIndexVersion;
import com.finguard.copilot.knowledge.ingest.KnowledgeDocumentRequest;
import com.finguard.copilot.knowledge.ingest.KnowledgeIngestionService;
import com.finguard.copilot.knowledge.persistence.KnowledgeDocumentRepository;
import com.finguard.copilot.knowledge.persistence.KnowledgeIndexRepository;

public final class KnowledgeIndexChangeService {

    private final KnowledgeIndexRepository indexRepository;
    private final KnowledgeDocumentRepository documentRepository;
    private final KnowledgeIngestionService ingestionService;
    private final KnowledgeIndexBuildService buildService;
    private final Supplier<String> candidateVersion;

    public KnowledgeIndexChangeService(KnowledgeIndexRepository indexRepository,
                                       KnowledgeDocumentRepository documentRepository,
                                       KnowledgeIngestionService ingestionService,
                                       KnowledgeIndexBuildService buildService,
                                       Supplier<String> candidateVersion) {
        if (indexRepository == null || documentRepository == null || ingestionService == null
                || buildService == null || candidateVersion == null) {
            throw new IllegalArgumentException("change dependencies must not be null");
        }
        this.indexRepository = indexRepository;
        this.documentRepository = documentRepository;
        this.ingestionService = ingestionService;
        this.buildService = buildService;
        this.candidateVersion = candidateVersion;
    }

    public KnowledgeIndexVersion replaceDocument(String documentId, String newVersion, byte[] sanitizedContent) {
        KnowledgeDocument currentDocument = document(documentId);
        requireVersion(newVersion);
        if (sanitizedContent == null || sanitizedContent.length == 0) {
            throw new IllegalArgumentException("sanitizedContent must not be empty");
        }
        KnowledgeDocument replacement = ingestionService.register(new KnowledgeDocumentRequest(
                documentId, currentDocument.sourceType(), currentDocument.title(), newVersion,
                new String(sanitizedContent, StandardCharsets.UTF_8)));
        return rebuildWithout(documentId, replacement.sourceVersion(), replacement);
    }

    public KnowledgeIndexVersion deleteDocument(String documentId, String documentVersion) {
        requireVersion(documentVersion);
        currentDocument(documentId, documentVersion);
        return rebuildWithout(documentId, documentVersion, null);
    }

    private KnowledgeIndexVersion rebuildWithout(String documentId, String documentVersion,
                                                 KnowledgeDocument replacement) {
        KnowledgeIndexVersion currentIndex = indexRepository.findPublished()
                .orElseThrow(() -> new IllegalStateException("no published index exists"));
        List<KnowledgeChunk> nextChunks = new ArrayList<>(indexRepository.findChunks(currentIndex.indexVersion()).stream()
                .filter(chunk -> !chunk.documentId().equals(documentId))
                .toList());
        if (replacement != null) {
            nextChunks.addAll(documentRepository.findChunks(documentId, documentVersion));
        }
        if (nextChunks.isEmpty()) {
            throw new IllegalStateException("change would create an empty knowledge index");
        }
        return buildService.build(candidateVersion.get(), nextChunks);
    }

    private KnowledgeDocument document(String documentId) {
        return documentRepository.findLatestByDocumentId(documentId)
                .orElseThrow(() -> new IllegalArgumentException("document does not exist: " + documentId));
    }

    private void currentDocument(String documentId, String documentVersion) {
        KnowledgeDocument document = documentRepository.findLatestByDocumentId(documentId).orElse(null);
        if (document == null || !document.sourceVersion().equals(documentVersion)
                || documentRepository.findChunks(documentId, documentVersion).isEmpty()) {
            throw new IllegalArgumentException("document does not exist: " + documentId);
        }
    }

    private static void requireVersion(String version) {
        if (version == null || version.isBlank()) {
            throw new IllegalArgumentException("documentVersion must not be blank");
        }
    }
}
