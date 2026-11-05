package com.finguard.copilot.agent.qualify;

import java.io.*;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import com.finguard.copilot.analysis.domain.AnalysisRecommendation;
import com.finguard.copilot.coreclient.port.ReviewTaskContext;

/** Frozen input and post-response scoring boundary; expected labels never become review facts. */
final class SemanticMainModelQualityData {
    static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules()
        .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    record Case(String caseId, String amount, String currency, String ruleCode, String reasonCode,
                String account, List<ReviewTaskContext.RiskFact> riskFacts, String publication,
                AnalysisRecommendation expected) {}

    static String approvedCasesSha(Map<String, String> environment) {
        String hash = environment.get("SEMANTIC_QUALITY_CASES_SHA256");
        if (!"true".equals(environment.get("SEMANTIC_QUALITY_APPROVED"))
                || hash == null || !hash.matches("[a-f0-9]{64}"))
            throw new IllegalArgumentException("Explicit approved frozen business cases required");
        return hash;
    }

    static List<Case> openCases(Path file, String approvedSha) throws IOException {
        verifyHash(file, approvedSha);
        var rows = new ArrayList<JsonNode>();
        for (String line : Files.readString(file).lines().toList()) {
            if (line.isBlank()) throw new IllegalArgumentException("Blank business case");
            rows.add(JSON.readTree(line));
        }
        // Verify once more to reject a changed file during input loading.
        verifyHash(file, approvedSha);
        return parseCases(rows);
    }

    static List<Case> parseCases(List<JsonNode> rows) {
        if (rows.size() != 11) throw new IllegalArgumentException("Exactly eleven component cases required");
        var result = new ArrayList<Case>();
        for (int i = 0; i < rows.size(); i++) {
            JsonNode row = rows.get(i);
            if (!row.isObject() || !"QW-%03d".formatted(i + 1).equals(text(row, "case_id"))
                    || !"p4-primary".equals(text(row, "scenario")))
                throw new IllegalArgumentException("Unexpected ordered business case identity");
            String publication = text(row, "publication");
            if (!(i == 10 ? "missing-policy" : "primary").equals(publication))
                throw new IllegalArgumentException("Unexpected publication case order");
            String amount = text(row, "amount");
            if (!amount.matches("(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?") || amount.length() > 100)
                throw new IllegalArgumentException("Invalid decimal amount string");
            new BigDecimal(amount);
            String currency = text(row, "currency");
            if (!currency.matches("[A-Z]{3}")) throw new IllegalArgumentException("Invalid currency");
            AnalysisRecommendation expected;
            try { expected = AnalysisRecommendation.valueOf(text(row, "expectedRecommendation")); }
            catch (RuntimeException invalid) { throw new IllegalArgumentException("Unknown expected recommendation"); }
            if (!row.path("risk_facts").isArray()) throw new IllegalArgumentException("Missing risk fact list");
            var risks = new ArrayList<ReviewTaskContext.RiskFact>();
            for (var risk : row.path("risk_facts")) risks.add(new ReviewTaskContext.RiskFact(
                text(risk, "code"), text(risk, "value"), text(risk, "source")));
            result.add(new Case(text(row, "case_id"), amount, currency, text(row, "rule_code"),
                text(row, "reason_code"), text(row, "account"), List.copyOf(risks), publication, expected));
        }
        return List.copyOf(result);
    }

    static boolean qualityPassed(boolean protocolPassed, AnalysisRecommendation expected, AnalysisRecommendation actual) {
        return protocolPassed && actual != null && expected == actual;
    }

    static String text(JsonNode node, String name) {
        JsonNode value = node.path(name);
        if (!value.isTextual() || value.asText().isBlank()) throw new IllegalArgumentException("Missing input field: " + name);
        return value.asText();
    }
    static void verifyHash(Path path, String expected) throws IOException {
        if (expected == null || !expected.matches("[a-f0-9]{64}") || Files.isSymbolicLink(path)
                || !sha256(path).equals(expected)) throw new IllegalArgumentException("Frozen input hash mismatch");
    }
    static String sha256(Path path) throws IOException {
        try (InputStream input = Files.newInputStream(path)) {
            MessageDigest digest = digest(); byte[] buffer = new byte[8192];
            for (int n; (n = input.read(buffer)) != -1;) digest.update(buffer, 0, n);
            return HexFormat.of().formatHex(digest.digest());
        }
    }
    static void writeNew(Path path, Object value) throws IOException {
        byte[] bytes = JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(value);
        try (var channel = FileChannel.open(path, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            var buffer = ByteBuffer.wrap(bytes);
            while (buffer.hasRemaining()) channel.write(buffer);
            channel.force(true);
        }
    }
    private static MessageDigest digest() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private SemanticMainModelQualityData() {}
}
