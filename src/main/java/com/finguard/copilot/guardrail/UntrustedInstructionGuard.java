package com.finguard.copilot.guardrail;

import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;
import java.text.Normalizer;

import com.finguard.copilot.analysis.domain.AnalysisRecommendation;
import com.finguard.copilot.common.error.CopilotException;
import com.finguard.copilot.common.error.ErrorCode;
import com.finguard.copilot.knowledge.retrieval.KnowledgeEvidence;

/** A narrow fail-closed check for known instruction-override patterns. */
public final class UntrustedInstructionGuard {
    private static final Pattern CHINESE_OVERRIDE = Pattern.compile(
            // Keep independent policy clauses separate, e.g. "低于阈值则忽略；规则版本为v1".
            "忽略[^\\r\\n。；;！!?]{0,24}(规则|政策|指令|系统|事实|证据|要求)");
    private static final Pattern ENGLISH_OVERRIDE = Pattern.compile(
            "\\bignore\\s+(?:(?:all|the)\\s+)?(?:(?:previous|prior|earlier|system)\\s+)?"
                    + "(?:instructions?|rules?|polic(?:y|ies))\\b",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    public void validate(String reviewerInput, List<KnowledgeEvidence> evidence,
                         AnalysisRecommendation recommendation) {
        Objects.requireNonNull(reviewerInput, "reviewerInput must not be null");
        Objects.requireNonNull(evidence, "evidence must not be null");
        Objects.requireNonNull(recommendation, "recommendation must not be null");
        if (recommendation == AnalysisRecommendation.INSUFFICIENT_EVIDENCE) {
            return;
        }
        if (containsOverride(reviewerInput) || evidence.stream().anyMatch(item -> containsOverride(item.excerpt()))) {
            throw new CopilotException(ErrorCode.FACT_VALIDATION_FAILED,
                    "directional output rejected for an untrusted instruction override");
        }
    }

    private boolean containsOverride(String text) {
        String normalized = Normalizer.normalize(text, Normalizer.Form.NFKC)
                .replaceAll("\\p{Cf}", "")
                .replaceAll("[\\r\\n]+", " ");
        return CHINESE_OVERRIDE.matcher(normalized).find() || ENGLISH_OVERRIDE.matcher(normalized).find();
    }
}
