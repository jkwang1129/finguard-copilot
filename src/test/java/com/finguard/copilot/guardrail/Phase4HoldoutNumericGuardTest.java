package com.finguard.copilot.guardrail;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finguard.copilot.analysis.domain.AnalysisRecommendation;
import com.finguard.copilot.analysis.domain.AnalysisResult;
import com.finguard.copilot.common.error.CopilotException;
import com.finguard.copilot.knowledge.domain.KnowledgeCitation;
import com.finguard.copilot.knowledge.retrieval.KnowledgeEvidence;
import com.finguard.copilot.coreclient.port.ReviewTaskContext;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class Phase4HoldoutNumericGuardTest {
    private static final Path DATASET = Path.of("tools/agent-evaluation/data/phase4-holdout-v1.jsonl");
    private static final String DATASET_SHA = "eb9146eb4c2325018b27963a2c62e0ce6a8847d00032a6fe3b91e325aaa5d3d2";
    private static final String POLICY = "Synthetic single threshold GTE 100.00 USD; no exemptions.";
    private static final Instant NOW = Instant.parse("2026-11-01T00:00:00Z");
    private static final UUID ID = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");

    @Test void frozenHoldoutDirectionalCasesAreAcceptedAndOppositeDirectionsRejected() throws Exception {
        assertThat(sha(Files.readAllBytes(DATASET))).isEqualTo(DATASET_SHA);
        var guard = new NumericThresholdGuard(List.of(new NumericThresholdGuard.Rule(
                "synthetic-v1", "SYNTH_LIMIT", "USD", NumericThresholdGuard.Operator.GTE,
                new BigDecimal("100.00"), "SYNTH_POLICY_V1", "v1", "Document", sha(POLICY.getBytes(StandardCharsets.UTF_8)))));
        var citation = new KnowledgeCitation("SYNTH_POLICY_V1", "v1", "chunk", "Document",
                sha(POLICY.getBytes(StandardCharsets.UTF_8)), 0.9, "synthetic-v1");
        var evidence = List.of(new KnowledgeEvidence(citation, POLICY));
        int checked = 0;
        for (String line : Files.readAllLines(DATASET, StandardCharsets.UTF_8)) {
            JsonNode row = new ObjectMapper().readTree(line);
            String expected = row.get("expected").asText();
            if ("INSUFFICIENT_EVIDENCE".equals(expected)) {
                continue; // Guard permits conservative refusal; no directional claim is tested.
            }
            BigDecimal amount = new BigDecimal(row.get("amount").asText());
            var facts = new ReviewTaskContext(
                    new ReviewTaskContext.ReviewTask(1, 0, "PENDING", row.get("rule_code").asText(), "SYNTH", NOW),
                    new ReviewTaskContext.Transaction(1, 1, amount, row.get("currency").asText(), NOW, "SYNTHETIC"),
                    new ReviewTaskContext.Reconciliation("MATCHED", "REFERENCE_ID", "SYNTH"), List.of(),
                    new ReviewTaskContext.AccountSummary("ACTIVE", "synthetic"), NOW);
            AnalysisRecommendation label = AnalysisRecommendation.valueOf(expected);
            AnalysisRecommendation opposite = label == AnalysisRecommendation.CONFIRMED
                    ? AnalysisRecommendation.IGNORED : AnalysisRecommendation.CONFIRMED;
            assertThatCode(() -> guard.validate(facts, result(amount, label), evidence))
                    .as(row.get("case_id").asText()).doesNotThrowAnyException();
            assertThatThrownBy(() -> guard.validate(facts, result(amount, opposite), evidence))
                    .as(row.get("case_id").asText()).isInstanceOf(CopilotException.class);
            checked++;
        }
        assertThat(checked).isEqualTo(11);
    }

    private AnalysisResult result(BigDecimal amount, AnalysisRecommendation recommendation) {
        return new AnalysisResult(AnalysisResult.CURRENT_SCHEMA_VERSION, ID, "summary", "explanation",
                List.of(new AnalysisResult.BusinessEvidence(1, 1, amount, "USD", "SYNTH_LIMIT", "SYNTH",
                        List.of("transaction.amount"))),
                List.of(new AnalysisResult.PolicyCitation("SYNTH_POLICY_V1", "v1", "Document", POLICY)),
                recommendation, "reason", List.of(), List.of());
    }

    private static String sha(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}
