package com.finguard.copilot.guardrail;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

import com.finguard.copilot.analysis.domain.AnalysisRecommendation;
import com.finguard.copilot.analysis.domain.AnalysisResult;
import com.finguard.copilot.common.error.CopilotException;
import com.finguard.copilot.common.error.ErrorCode;
import com.finguard.copilot.coreclient.port.ReviewTaskContext;
import com.finguard.copilot.knowledge.retrieval.KnowledgeEvidence;

/** Project review policy v1, applied only after the Core risk-hit facts are validated. */
public final class CoreLargeAmountPolicyGuard {
    public static final String DOCUMENT_ID = "finguard-large-amount-review-policy";
    public static final String CONTENT_SHA256 = "4b4ea96b87ab65ff6e1be975b36b81279058351829ecb652aebb9306a3a2e556";

    public void validate(ReviewTaskContext facts, AnalysisResult result, List<KnowledgeEvidence> evidence) {
        if (!"LARGE_AMOUNT".equals(facts.reviewTask().ruleCode())
                || result.recommendation() == AnalysisRecommendation.INSUFFICIENT_EVIDENCE) {
            return;
        }
        List<KnowledgeEvidence> matches = evidence.stream().filter(item ->
                DOCUMENT_ID.equals(item.citation().documentId())
                        && "v1".equals(item.citation().documentVersion())
                        && "Document".equals(item.citation().sectionPath())
                        && CONTENT_SHA256.equals(item.citation().contentChecksum())
                        && CONTENT_SHA256.equals(sha256(item.excerpt()))).toList();
        if (matches.size() != 1 || result.recommendation() != AnalysisRecommendation.CONFIRMED
                || result.policyCitations().stream().noneMatch(citation ->
                        DOCUMENT_ID.equals(citation.documentId()) && "v1".equals(citation.version())
                                && "Document".equals(citation.sourcePath())
                                && matches.get(0).excerpt().equals(citation.excerpt()))) {
            throw new CopilotException(ErrorCode.FACT_VALIDATION_FAILED,
                    "directional output does not match the project large amount review policy");
        }
    }

    private String sha256(String content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(content.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
