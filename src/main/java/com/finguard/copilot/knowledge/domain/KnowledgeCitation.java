package com.finguard.copilot.knowledge.domain;

public record KnowledgeCitation(
        String documentId,
        String documentVersion,
        String chunkId,
        String sectionPath,
        String contentChecksum,
        double score,
        String indexVersion
) {

    public KnowledgeCitation {
        requireText(documentId, "documentId");
        requireText(documentVersion, "documentVersion");
        requireText(chunkId, "chunkId");
        sectionPath = KnowledgeChunk.normalizeSectionPath(sectionPath);
        requireText(contentChecksum, "contentChecksum");
        if (!Double.isFinite(score) || score < -1 || score > 1) {
            throw new IllegalArgumentException("score must be finite and between -1 and 1");
        }
        requireText(indexVersion, "indexVersion");
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
