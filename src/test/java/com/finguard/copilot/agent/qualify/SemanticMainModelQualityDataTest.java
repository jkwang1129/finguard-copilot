package com.finguard.copilot.agent.qualify;

import java.nio.file.*;
import java.util.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.finguard.copilot.analysis.domain.AnalysisRecommendation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class SemanticMainModelQualityDataTest {
    @TempDir Path directory;

    @Test void requiresExactOrderedUniqueCaseIdentities() throws Exception {
        var rows = rows();
        assertEquals(11, SemanticMainModelQualityData.parseCases(rows).size());
        ((ObjectNode) rows.get(1)).put("case_id", "QW-001");
        assertThrows(IllegalArgumentException.class, () -> SemanticMainModelQualityData.parseCases(rows));
        var reordered = rows();
        Collections.swap(reordered, 0, 1);
        assertThrows(IllegalArgumentException.class, () -> SemanticMainModelQualityData.parseCases(reordered));
    }

    @Test void approvalHashBindsEveryByteBeforeParsing() throws Exception {
        Path path = directory.resolve("cases.jsonl");
        Files.writeString(path, "original\n");
        String hash = SemanticMainModelQualityData.sha256(path);
        assertDoesNotThrow(() -> SemanticMainModelQualityData.verifyHash(path, hash));
        Files.writeString(path, "original\n ");
        assertThrows(IllegalArgumentException.class, () -> SemanticMainModelQualityData.verifyHash(path, hash));
        assertThrows(IllegalArgumentException.class, () -> SemanticMainModelQualityData.verifyHash(path, null));
    }

    @Test void paidApprovalRequiresExactTrueAndAHash() {
        String hash = "a".repeat(64);
        assertEquals(hash, SemanticMainModelQualityData.approvedCasesSha(Map.of(
            "SEMANTIC_QUALITY_APPROVED", "true", "SEMANTIC_QUALITY_CASES_SHA256", hash)));
        assertThrows(IllegalArgumentException.class, () -> SemanticMainModelQualityData.approvedCasesSha(Map.of(
            "SEMANTIC_QUALITY_APPROVED", "TRUE", "SEMANTIC_QUALITY_CASES_SHA256", hash)));
        assertThrows(IllegalArgumentException.class, () -> SemanticMainModelQualityData.approvedCasesSha(Map.of(
            "SEMANTIC_QUALITY_APPROVED", "true")));
    }

    @Test void rejectsUnknownExpectedLabelAndWrongPublication() throws Exception {
        var unknown = rows();
        ((ObjectNode) unknown.get(0)).put("expectedRecommendation", "SAFE");
        assertThrows(IllegalArgumentException.class, () -> SemanticMainModelQualityData.parseCases(unknown));
        var wrong = rows();
        ((ObjectNode) wrong.get(10)).put("publication", "primary");
        assertThrows(IllegalArgumentException.class, () -> SemanticMainModelQualityData.parseCases(wrong));
    }

    @Test void positiveCannotPassByAbstainingOrProtocolAlone() {
        assertFalse(SemanticMainModelQualityData.qualityPassed(true, AnalysisRecommendation.CONFIRMED,
            AnalysisRecommendation.INSUFFICIENT_EVIDENCE));
        assertFalse(SemanticMainModelQualityData.qualityPassed(false, AnalysisRecommendation.CONFIRMED,
            AnalysisRecommendation.CONFIRMED));
        assertFalse(SemanticMainModelQualityData.qualityPassed(true, AnalysisRecommendation.CONFIRMED, null));
        assertTrue(SemanticMainModelQualityData.qualityPassed(true, AnalysisRecommendation.CONFIRMED,
            AnalysisRecommendation.CONFIRMED));
        assertTrue(SemanticMainModelQualityData.qualityPassed(true, AnalysisRecommendation.INSUFFICIENT_EVIDENCE,
            AnalysisRecommendation.INSUFFICIENT_EVIDENCE));
    }

    private static ArrayList<com.fasterxml.jackson.databind.JsonNode> rows() throws Exception {
        var result = new ArrayList<com.fasterxml.jackson.databind.JsonNode>();
        for (int i = 1; i <= 11; i++) {
            var row = SemanticMainModelQualityData.JSON.createObjectNode();
            row.put("case_id", "QW-%03d".formatted(i)); row.put("scenario", "p4-primary");
            row.put("amount", "100.00"); row.put("currency", "USD"); row.put("account", "SYNTHETIC-ACCOUNT");
            row.put("rule_code", "SYNTH_LIMIT"); row.put("reason_code", "REVIEW"); row.putArray("risk_facts");
            row.put("publication", i == 11 ? "missing-policy" : "primary");
            row.put("expectedRecommendation", i == 11 ? "INSUFFICIENT_EVIDENCE" : "CONFIRMED");
            result.add(row);
        }
        return result;
    }
}
