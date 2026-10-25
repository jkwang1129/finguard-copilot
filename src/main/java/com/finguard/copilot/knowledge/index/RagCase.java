package com.finguard.copilot.knowledge.index;

import java.util.List;

public record RagCase(
        String caseId,
        String query,
        List<String> expectedChunkIds,
        String requiredCitationDocumentId,
        String requiredCitationDocumentVersion,
        List<String> forbiddenChunkIds
) {

    public RagCase {
        requireText(caseId, "caseId");
        requireText(query, "query");
        expectedChunkIds = immutableTextList(expectedChunkIds, "expectedChunkIds");
        forbiddenChunkIds = immutableTextList(forbiddenChunkIds, "forbiddenChunkIds");
        if ((requiredCitationDocumentId == null) != (requiredCitationDocumentVersion == null)) {
            throw new IllegalArgumentException("required citation document id and version must be supplied together");
        }
        if (requiredCitationDocumentId != null) {
            requireText(requiredCitationDocumentId, "requiredCitationDocumentId");
            requireText(requiredCitationDocumentVersion, "requiredCitationDocumentVersion");
        }
    }

    private static List<String> immutableTextList(List<String> values, String name) {
        if (values == null || values.stream().anyMatch(value -> value == null || value.isBlank())) {
            throw new IllegalArgumentException(name + " must not contain blank values");
        }
        return List.copyOf(values);
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
