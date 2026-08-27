package com.finguard.copilot.knowledge.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Map;

public record KnowledgeChunk(
        String chunkId,
        String documentId,
        String documentVersion,
        int ordinal,
        String content,
        String contentChecksum,
        String sectionPath,
        Map<String, String> metadata
) {

    public KnowledgeChunk {
        requireText(chunkId, "chunkId");
        requireText(documentId, "documentId");
        requireText(documentVersion, "documentVersion");
        if (ordinal < 0) {
            throw new IllegalArgumentException("ordinal must be >= 0");
        }
        requireText(content, "content");
        requireText(contentChecksum, "contentChecksum");
        sectionPath = normalizeSectionPath(sectionPath);
        if (metadata == null || metadata.entrySet().stream()
                .anyMatch(entry -> isBlank(entry.getKey()) || isBlank(entry.getValue()))) {
            throw new IllegalArgumentException("metadata must not contain blank entries");
        }
        metadata = Map.copyOf(metadata);
    }

    public static KnowledgeChunk create(
            String documentId,
            String documentVersion,
            int ordinal,
            String content,
            String sectionPath,
            Map<String, String> metadata
    ) {
        requireText(documentId, "documentId");
        requireText(documentVersion, "documentVersion");
        return new KnowledgeChunk(
                documentId + ":" + documentVersion + ":" + ordinal,
                documentId,
                documentVersion,
                ordinal,
                content,
                sha256(content),
                sectionPath,
                metadata);
    }

    static String normalizeSectionPath(String value) {
        requireText(value, "sectionPath");
        String normalized = value.trim().replaceAll("\\s*/\\s*", "/");
        while (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        normalized = normalized.trim();
        requireText(normalized, "sectionPath");
        return normalized;
    }

    private static String sha256(String value) {
        requireText(value, "content");
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static void requireText(String value, String name) {
        if (isBlank(value)) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
