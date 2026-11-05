package com.finguard.copilot.agent.qualify;

import java.nio.file.*;
import java.util.*;
import com.fasterxml.jackson.databind.JsonNode;

/** Corpus-only boundary: no development/holdout input or query API exists here. */
final class SemanticRequalificationPreparationData {
    static final String ORIGINAL_SHA = "c66bac3d56a163141b0aee29460cc00cfb549fa9bfd625cf53a82a9c04c9b386";
    static final String CORPUS_SHA = "9fdd43cb211ec5a230f4deaaaf6e0c1d1a7bbc6b8b47e61b82e59e21698fe982";
    static final String EMBEDDING_VERSION = "e5-small-int8-55102a6d5c23b708db337173";
    static final String EMBEDDING_SHA = "55102a6d5c23b708db337173e13e93954b1d9cf0fb7632cfa3d2ce42b643d10d";
    static final double MINIMUM_SCORE = 0.8569634500000001;
    record Inputs(JsonNode original, List<JsonNode> corpus) {}

    static Inputs open(Path original, Path corpus) throws Exception {
        if (Files.isSymbolicLink(original) || !ORIGINAL_SHA.equals(SemanticRagPreparationData.sha256(original)))
            throw new IllegalArgumentException("Frozen original preparation changed");
        if (Files.isSymbolicLink(corpus) || !CORPUS_SHA.equals(SemanticRagPreparationData.sha256(corpus)))
            throw new IllegalArgumentException("Frozen corpus changed");
        var json = SemanticRagPreparationData.JSON;
        JsonNode parent = json.readTree(Files.readAllBytes(original));
        if (!"RAG_EVALUATED".equals(parent.path("status").asText())
                || parent.path("minimumScore").doubleValue() != MINIMUM_SCORE
                || parent.path("holdoutQualityPassed").asBoolean(true)
                || !EMBEDDING_VERSION.equals(parent.path("embeddingVersion").asText())
                || !EMBEDDING_SHA.equals(parent.path("embeddingManifestSha256").asText())
                || !CORPUS_SHA.equals(parent.path("fileSha256").path("corpus.jsonl").asText()))
            throw new IllegalArgumentException("Original preparation identity mismatch");
        var rows = new ArrayList<JsonNode>();
        var ids = new HashSet<String>();
        for (String line : Files.readString(corpus).lines().toList()) {
            JsonNode row = json.readTree(line);
            if (row == null || !row.isObject() || !ids.add(text(row,"id")) || !"v1".equals(text(row,"version")))
                throw new IllegalArgumentException("Invalid frozen corpus identity");
            text(row,"title");text(row,"content");rows.add(row);
        }
        if (rows.size() != 8 || !ids.equals(Set.of("account-status","data-retention","euro-review",
                "incident-infrastructure","manual-audit","settlement-reconciliation","support-faq","synth-usd-limit")))
            throw new IllegalArgumentException("Expected eight frozen corpus sources");
        // Refuse a concurrent byte change between the hash check and parse.
        if (!ORIGINAL_SHA.equals(SemanticRagPreparationData.sha256(original))
                || !CORPUS_SHA.equals(SemanticRagPreparationData.sha256(corpus)))
            throw new IllegalArgumentException("Frozen preparation inputs changed during loading");
        return new Inputs(parent,List.copyOf(rows));
    }
    static String text(JsonNode row,String key) {
        JsonNode value=row.path(key);
        if (!value.isTextual() || value.asText().isBlank()) throw new IllegalArgumentException("Invalid frozen corpus text");
        return value.asText();
    }
}
