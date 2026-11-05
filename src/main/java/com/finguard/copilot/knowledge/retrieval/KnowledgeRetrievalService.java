package com.finguard.copilot.knowledge.retrieval;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Map;
import java.util.Comparator;

import com.finguard.copilot.knowledge.domain.KnowledgeCitation;
import com.finguard.copilot.knowledge.persistence.KnowledgeDocumentRepository;
import com.finguard.copilot.knowledge.persistence.KnowledgeIndexRepository;
import com.finguard.copilot.knowledge.port.VectorDocument;
import com.finguard.copilot.knowledge.port.EmbeddingGateway;
import com.finguard.copilot.knowledge.port.VectorSearchQuery;
import com.finguard.copilot.knowledge.port.VectorStore;

public final class KnowledgeRetrievalService {

    private final KnowledgeIndexRepository repository;
    private final EmbeddingGateway embeddingGateway;
    private final VectorStore vectorStore;
    private final String embeddingVersion;
    private final int maximumTopK;
    private final double minimumScore;
    private final KnowledgeDocumentRepository documentRepository;
    private final double minimumMargin;

    public KnowledgeRetrievalService(KnowledgeIndexRepository repository, EmbeddingGateway embeddingGateway,
                                     VectorStore vectorStore, String embeddingVersion,
                                     int maximumTopK, double minimumScore) {
        this(repository, embeddingGateway, vectorStore, embeddingVersion, maximumTopK, minimumScore, null);
    }

    public KnowledgeRetrievalService(KnowledgeIndexRepository repository, EmbeddingGateway embeddingGateway,
                                     VectorStore vectorStore, String embeddingVersion,
                                     int maximumTopK, double minimumScore,
                                     KnowledgeDocumentRepository documentRepository) {
        this(repository, embeddingGateway, vectorStore, embeddingVersion, maximumTopK, minimumScore, documentRepository, 0);
    }

    public KnowledgeRetrievalService(KnowledgeIndexRepository repository, EmbeddingGateway embeddingGateway,
                                     VectorStore vectorStore, String embeddingVersion,
                                     int maximumTopK, double minimumScore,
                                     KnowledgeDocumentRepository documentRepository, double minimumMargin) {
        if (repository == null || embeddingGateway == null || vectorStore == null) {
            throw new IllegalArgumentException("retrieval dependencies must not be null");
        }
        if (embeddingVersion == null || embeddingVersion.isBlank() || maximumTopK < 1
                || !Double.isFinite(minimumScore) || minimumScore < -1 || minimumScore > 1) {
            throw new IllegalArgumentException("retrieval parameters are invalid");
        }
        if (!Double.isFinite(minimumMargin) || minimumMargin < 0 || minimumMargin > 2
                || (minimumMargin > 0 && (maximumTopK != 1 || documentRepository == null))) {
            throw new IllegalArgumentException("margin retrieval requires a finite gap in [0,2], topK 1 and SQL documents");
        }
        this.repository = repository;
        this.embeddingGateway = embeddingGateway;
        this.vectorStore = vectorStore;
        this.embeddingVersion = embeddingVersion;
        this.maximumTopK = maximumTopK;
        this.minimumScore = minimumScore;
        this.documentRepository = documentRepository;
        this.minimumMargin = minimumMargin;
    }

    public double minimumMargin() { return minimumMargin; }

    public Map<String,Object> descriptor() {
        if (minimumMargin == 0) return Map.of();
        return Map.of("mode", "single-source-margin-v1", "candidateTopK", 2, "topK", 1,
                "minimumScore", minimumScore, "minimumMargin", minimumMargin,
                "duplicateSource", "abstain", "singleCandidate", "abstain", "candidateSelection", "raw-before-filter");
    }

    public List<KnowledgeEvidence> retrieve(String query, int topK) {
        return retrieveBatch(query,topK).evidence();
    }

    public record RetrievalBatch(String indexVersion,List<KnowledgeEvidence> evidence) {
        public RetrievalBatch { evidence=List.copyOf(evidence); }
    }

    public RetrievalBatch retrieveBatch(String query,int topK) {
        if (query == null || query.isBlank() || topK < 1 || topK > maximumTopK) {
            throw new IllegalArgumentException("retrieval query parameters are invalid");
        }
        String indexVersion = repository.findPublished()
                .map(value -> value.indexVersion())
                .orElseThrow(() -> new KnowledgeRetrievalException(
                        KnowledgeRetrievalException.KNOWLEDGE_UNAVAILABLE + ": no published index"));
        if (minimumMargin > 0) return retrievePinned(indexVersion, query, topK);
        try {
            var evidence=vectorStore.search(new VectorSearchQuery(
                            indexVersion, embeddingGateway.embedQuery(query, embeddingVersion), topK)).stream()
                    .filter(result -> result.score() >= minimumScore)
                    .map(result -> toEvidence(indexVersion, result.document(), result.score()))
                    .toList();
            return new RetrievalBatch(indexVersion,evidence);
        } catch (KnowledgeRetrievalException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new KnowledgeRetrievalException(
                    KnowledgeRetrievalException.KNOWLEDGE_UNAVAILABLE + ": vector search failed", exception);
        }
    }

    private KnowledgeEvidence toEvidence(String indexVersion, VectorDocument document, double score) {
        Map<String, String> metadata = document.metadata();
        String checksum = required(metadata, "contentChecksum");
        String content = document.content();
        if (content.isBlank()) {
            if (documentRepository == null) {
                throw new KnowledgeRetrievalException(
                        KnowledgeRetrievalException.KNOWLEDGE_UNAVAILABLE + ": vector result has no content source");
            }
            String documentId = required(metadata, "documentId");
            String documentVersion = required(metadata, "documentVersion");
            String chunkId = required(metadata, "chunkId");
            content = documentRepository.findChunks(documentId, documentVersion).stream()
                    .filter(chunk -> chunk.chunkId().equals(chunkId))
                    .map(chunk -> chunk.content())
                    .findFirst()
                    .orElseThrow(() -> new KnowledgeRetrievalException(
                            KnowledgeRetrievalException.KNOWLEDGE_UNAVAILABLE + ": vector chunk is not registered"));
        }
        if (!checksum.equals(sha256(content))) {
            throw new KnowledgeRetrievalException(
                    KnowledgeRetrievalException.KNOWLEDGE_UNAVAILABLE + ": content checksum mismatch");
        }
        return new KnowledgeEvidence(new KnowledgeCitation(
                required(metadata, "documentId"), required(metadata, "documentVersion"),
                required(metadata, "chunkId"), required(metadata, "sectionPath"), checksum, score, indexVersion), content);
    }

    /** Agent reads cannot silently switch to a newer publication or keep serving a withdrawn version. */
    public RetrievalBatch retrievePinned(String indexVersion, String query, int topK) {
        if(indexVersion==null || query==null || query.isBlank() || topK<1 || topK>maximumTopK) {
            throw new IllegalArgumentException("pinned retrieval parameters are invalid");
        }
        requirePublished(indexVersion);
        if (minimumMargin > 0) return retrieveMargin(indexVersion, query);
        try {
            var membership=repository.findChunks(indexVersion);
            var evidence=vectorStore.search(new VectorSearchQuery(indexVersion,
                    embeddingGateway.embedQuery(query,embeddingVersion),topK)).stream()
                .filter(result->result.score()>=minimumScore)
                .map(result->{
                    if(!indexVersion.equals(result.document().indexVersion())) {
                        throw new KnowledgeRetrievalException("KNOWLEDGE_UNAVAILABLE: index mismatch");
                    }
                    var item=toEvidence(indexVersion,result.document(),result.score());
                    if(membership.stream().noneMatch(chunk->chunk.chunkId().equals(item.citation().chunkId())
                        && chunk.documentId().equals(item.citation().documentId())
                        && chunk.documentVersion().equals(item.citation().documentVersion())
                        && chunk.contentChecksum().equals(item.citation().contentChecksum()))) {
                        throw new KnowledgeRetrievalException("KNOWLEDGE_UNAVAILABLE: chunk is not an index member");
                    }
                    return item;
                }).toList();
            requirePublished(indexVersion);
            return new RetrievalBatch(indexVersion,evidence);
        } catch(KnowledgeRetrievalException exception){throw exception;}
        catch(RuntimeException exception){throw new KnowledgeRetrievalException("KNOWLEDGE_UNAVAILABLE: pinned retrieval failed");}
    }

    private RetrievalBatch retrieveMargin(String indexVersion, String query) {
        try {
            requireCurrentPublication(indexVersion);
            var membership = repository.findChunks(indexVersion);
            var raw = vectorStore.search(new VectorSearchQuery(indexVersion,
                    embeddingGateway.embedQuery(query, embeddingVersion), 2)).stream()
                    .sorted(Comparator.comparingDouble(com.finguard.copilot.knowledge.port.VectorSearchResult::score).reversed()
                        .thenComparing(result -> result.document().id()))
                    .limit(2).toList();
            var validated = raw.stream().map(result -> {
                if (!indexVersion.equals(result.document().indexVersion()))
                    throw new KnowledgeRetrievalException("KNOWLEDGE_UNAVAILABLE: index mismatch");
                var evidence = toEvidence(indexVersion, result.document(), result.score());
                var citation = evidence.citation();
                var sqlChunk = documentRepository.findChunks(citation.documentId(), citation.documentVersion()).stream()
                    .filter(chunk -> chunk.chunkId().equals(citation.chunkId())
                        && chunk.documentId().equals(citation.documentId()) && chunk.documentVersion().equals(citation.documentVersion())
                        && chunk.sectionPath().equals(citation.sectionPath())
                        && chunk.contentChecksum().equals(citation.contentChecksum())
                        && chunk.contentChecksum().equals(sha256(chunk.content())) && chunk.content().equals(evidence.excerpt()))
                    .findFirst().orElseThrow(() -> new KnowledgeRetrievalException("KNOWLEDGE_UNAVAILABLE: SQL chunk mismatch"));
                if (membership.stream().noneMatch(chunk -> chunk.chunkId().equals(sqlChunk.chunkId())
                    && chunk.documentId().equals(sqlChunk.documentId()) && chunk.documentVersion().equals(sqlChunk.documentVersion())
                    && chunk.sectionPath().equals(sqlChunk.sectionPath()) && chunk.contentChecksum().equals(sqlChunk.contentChecksum())
                    && chunk.content().equals(sqlChunk.content())))
                    throw new KnowledgeRetrievalException("KNOWLEDGE_UNAVAILABLE: chunk is not an index member");
                return evidence;
            }).toList();
            requirePublished(indexVersion);
            requireCurrentPublication(indexVersion);
            if (validated.size() < 2 || validated.get(0).citation().documentId().equals(validated.get(1).citation().documentId()))
                return new RetrievalBatch(indexVersion, List.of());
            var winner = validated.get(0);
            boolean accepted = winner.citation().score() >= minimumScore
                && winner.citation().score() - validated.get(1).citation().score() >= minimumMargin;
            return new RetrievalBatch(indexVersion, accepted ? List.of(winner) : List.of());
        } catch (KnowledgeRetrievalException exception) { throw exception; }
        catch (RuntimeException exception) {
            throw new KnowledgeRetrievalException("KNOWLEDGE_UNAVAILABLE: margin retrieval failed", exception);
        }
    }

    private void requireCurrentPublication(String indexVersion) {
        if (repository.findPublished().filter(index -> index.indexVersion().equals(indexVersion)
                && index.status() == com.finguard.copilot.knowledge.domain.KnowledgeIndexStatus.PUBLISHED).isEmpty())
            throw new KnowledgeRetrievalException("KNOWLEDGE_UNAVAILABLE: published index changed");
    }

    private void requirePublished(String indexVersion) {
        if(repository.find(indexVersion).filter(index->index.status()==
                com.finguard.copilot.knowledge.domain.KnowledgeIndexStatus.PUBLISHED).isEmpty()) {
            throw new KnowledgeRetrievalException("KNOWLEDGE_UNAVAILABLE: frozen index is not published");
        }
    }

    private String required(Map<String, String> metadata, String key) {
        String value = metadata == null ? null : metadata.get(key);
        if (value == null || value.isBlank()) {
            throw new KnowledgeRetrievalException(
                    KnowledgeRetrievalException.KNOWLEDGE_UNAVAILABLE + ": missing vector metadata " + key);
        }
        return value;
    }

    private String sha256(String value) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
