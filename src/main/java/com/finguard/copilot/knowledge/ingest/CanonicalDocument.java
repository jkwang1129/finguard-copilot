package com.finguard.copilot.knowledge.ingest;

public record CanonicalDocument(
        String documentId,
        String documentVersion,
        String text,
        String checksum
) {

    public CanonicalDocument(String text, String checksum) {
        this(null, null, text, checksum);
    }

    public CanonicalDocument {
        requireText(text, "text");
        requireText(checksum, "checksum");
        if ((documentId == null) != (documentVersion == null)) {
            throw new IllegalArgumentException("documentId and documentVersion must be supplied together");
        }
        if (documentId != null) {
            requireText(documentId, "documentId");
            requireText(documentVersion, "documentVersion");
        }
    }

    public CanonicalDocument withIdentity(String documentId, String documentVersion) {
        return new CanonicalDocument(documentId, documentVersion, text, checksum);
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
