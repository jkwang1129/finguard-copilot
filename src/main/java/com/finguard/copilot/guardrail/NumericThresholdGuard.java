package com.finguard.copilot.guardrail;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Objects;
import java.util.stream.Collectors;

import com.finguard.copilot.analysis.domain.AnalysisRecommendation;
import com.finguard.copilot.analysis.domain.AnalysisResult;
import com.finguard.copilot.common.error.CopilotException;
import com.finguard.copilot.common.error.ErrorCode;
import com.finguard.copilot.coreclient.port.ReviewTaskContext;
import com.finguard.copilot.knowledge.retrieval.KnowledgeEvidence;

/** Validates only explicitly configured and evidence-bound single-threshold rules. */
public final class NumericThresholdGuard {
    private final Map<String, Rule> rulesByCode;

    public NumericThresholdGuard(List<Rule> rules) {
        Objects.requireNonNull(rules, "numeric rules must not be null");
        try {
            this.rulesByCode = rules.stream().collect(Collectors.toUnmodifiableMap(Rule::ruleCode, rule -> rule));
        } catch (IllegalStateException exception) {
            throw new IllegalArgumentException("numeric rule codes must be unique", exception);
        }
    }

    public void validate(ReviewTaskContext facts, AnalysisResult result, List<KnowledgeEvidence> evidence) {
        Objects.requireNonNull(facts, "facts must not be null");
        Objects.requireNonNull(result, "result must not be null");
        Objects.requireNonNull(evidence, "evidence must not be null");
        if (result.recommendation() == AnalysisRecommendation.INSUFFICIENT_EVIDENCE) {
            return;
        }
        String ruleCode = facts.reviewTask().ruleCode();
        if (ruleCode == null) {
            throw rejected();
        }
        Rule rule = rulesByCode.get(ruleCode);
        if (rule == null) {
            return; // This rule is unmanaged; no numeric validation is claimed.
        }
        if (!rule.currency().equals(facts.transaction().currency())) {
            throw rejected();
        }
        List<KnowledgeEvidence> matches = evidence.stream()
                .filter(item -> rule.documentId().equals(item.citation().documentId())
                        && rule.documentVersion().equals(item.citation().documentVersion())
                        && rule.sectionPath().equals(item.citation().sectionPath())
                        && rule.contentSha256().equals(item.citation().contentChecksum())
                        && rule.contentSha256().equals(sha256(item.excerpt())))
                .toList();
        if (matches.size() != 1 || evidence.size() != 1) {
            throw rejected();
        }
        KnowledgeEvidence matched = matches.get(0);
        boolean cited = result.policyCitations().stream().anyMatch(citation ->
                rule.documentId().equals(citation.documentId())
                        && rule.documentVersion().equals(citation.version())
                        && rule.sectionPath().equals(citation.sourcePath())
                        && matched.excerpt().equals(citation.excerpt()));
        if (!cited) {
            throw rejected();
        }
        int ordering = facts.transaction().amount().compareTo(rule.threshold());
        boolean triggered = switch (rule.operator()) {
            case GT -> ordering > 0;
            case GTE -> ordering >= 0;
            case LT -> ordering < 0;
            case LTE -> ordering <= 0;
        };
        AnalysisRecommendation expected = triggered ? AnalysisRecommendation.CONFIRMED : AnalysisRecommendation.IGNORED;
        if (result.recommendation() != expected) {
            throw rejected();
        }
    }

    public Set<String> ruleCodes() { return rulesByCode.keySet(); }

    public enum Operator { GT, GTE, LT, LTE }

    /**
     * A reviewed rule asserts that its pinned policy has no additional conditions or exemptions.
     * It is configuration, never extracted from a model response or document text.
     */
    public record Rule(String version, String ruleCode, String currency, Operator operator, BigDecimal threshold,
                       String documentId, String documentVersion, String sectionPath, String contentSha256) {
        public Rule {
            requireText(version, "version");
            requireText(ruleCode, "ruleCode");
            if ("LARGE_AMOUNT".equals(ruleCode)) {
                throw new IllegalArgumentException("Core large amount hit does not define a review decision");
            }
            requireText(documentId, "documentId");
            requireText(documentVersion, "documentVersion");
            requireText(sectionPath, "sectionPath");
            if (currency == null || !currency.matches("[A-Z]{3}") || operator == null
                    || threshold == null || threshold.signum() < 0
                    || contentSha256 == null || !contentSha256.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException("numeric rule is invalid");
            }
        }

        private static void requireText(String value, String name) {
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException(name + " must not be blank");
            }
        }
    }

    private static String sha256(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static CopilotException rejected() {
        return new CopilotException(ErrorCode.FACT_VALIDATION_FAILED,
                "directional output could not be verified against its configured numeric rule");
    }
}
