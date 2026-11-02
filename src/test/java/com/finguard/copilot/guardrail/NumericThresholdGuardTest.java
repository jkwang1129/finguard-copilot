package com.finguard.copilot.guardrail;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

import com.finguard.copilot.analysis.domain.AnalysisRecommendation;
import com.finguard.copilot.analysis.domain.AnalysisResult;
import com.finguard.copilot.common.error.CopilotException;
import com.finguard.copilot.common.error.ErrorCode;
import com.finguard.copilot.coreclient.port.ReviewTaskContext;
import com.finguard.copilot.knowledge.domain.KnowledgeCitation;
import com.finguard.copilot.knowledge.retrieval.KnowledgeEvidence;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NumericThresholdGuardTest {
    private static final UUID ID = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final Instant NOW = Instant.parse("2026-10-27T00:00:00Z");
    private static final String POLICY = "人工批准的单阈值政策，按原始十进制值比较，不存在其他条件或豁免。";

    @ParameterizedTest
    @CsvSource({
            "0.100000000000000001, GT, 0.100000000000000000, CONFIRMED",
            "0.100000000000000000, GT, 0.100000000000000000, IGNORED",
            "0.100000000000000000, GTE, 0.100000000000000000, CONFIRMED",
            "0.099999999999999999, GTE, 0.100000000000000000, IGNORED",
            "0.099999999999999999, LT, 0.100000000000000000, CONFIRMED",
            "0.100000000000000000, LT, 0.100000000000000000, IGNORED",
            "0.100000000000000000, LTE, 0.100000000000000000, CONFIRMED"
    })
    void comparesFullPrecisionUsingOnlyTheConfiguredOperator(String amount,
            NumericThresholdGuard.Operator operator, String threshold, AnalysisRecommendation expected) {
        NumericThresholdGuard guard = new NumericThresholdGuard(List.of(rule(operator, threshold)));
        assertThatCode(() -> guard.validate(facts(amount, "USD"), result(amount, "USD", POLICY, expected, true),
                List.of(evidence(POLICY, hash(POLICY))))).doesNotThrowAnyException();
    }

    @Test
    void rejectsH03WrongDirection() {
        assertRejected(guard(), facts("0.100000000000000001", "USD"),
                result("0.100000000000000001", "USD", POLICY, AnalysisRecommendation.IGNORED, true),
                List.of(evidence(POLICY, hash(POLICY))));
    }

    @Test
    void requiresRegisteredEvidenceAndExactCitationEvenWhenRetrievalReturnsNothing() {
        ReviewTaskContext facts = facts("0.100000000000000001", "USD");
        AnalysisResult result = result("0.100000000000000001", "USD", POLICY,
                AnalysisRecommendation.CONFIRMED, true);
        assertRejected(guard(), facts, result, List.of());
        assertRejected(guard(), facts, result("0.100000000000000001", "USD", POLICY,
                AnalysisRecommendation.CONFIRMED, false), List.of(evidence(POLICY, hash(POLICY))));
    }

    @Test
    void rejectsChangedPolicyAndSpoofedChecksumMetadata() {
        String altered = POLICY + " 但是 ACTIVE 账户豁免。";
        ReviewTaskContext facts = facts("0.100000000000000001", "USD");
        AnalysisResult result = result("0.100000000000000001", "USD", altered,
                AnalysisRecommendation.CONFIRMED, true);
        assertRejected(guard(), facts, result, List.of(evidence(altered, hash(altered))));
        assertRejected(guard(), facts, result, List.of(evidence(altered, hash(POLICY))));
    }

    @Test
    void rejectsCurrencyMismatchAndWrongDocumentVersion() {
        assertRejected(guard(), facts("100", "AUD"),
                result("100", "AUD", POLICY, AnalysisRecommendation.CONFIRMED, true),
                List.of(evidence(POLICY, hash(POLICY))));
        KnowledgeCitation wrongVersion = new KnowledgeCitation("HO-P03", "2.0", "chunk", "threshold",
                hash(POLICY), 0.9, "published-v1");
        assertRejected(guard(), facts("100", "USD"),
                result("100", "USD", POLICY, AnalysisRecommendation.CONFIRMED, true),
                List.of(new KnowledgeEvidence(wrongVersion, POLICY)));
        KnowledgeCitation wrongDocument = new KnowledgeCitation("OTHER", "1.0", "chunk", "threshold",
                hash(POLICY), 0.9, "published-v1");
        KnowledgeCitation wrongSection = new KnowledgeCitation("HO-P03", "1.0", "chunk", "other",
                hash(POLICY), 0.9, "published-v1");
        assertRejected(guard(), facts("100", "USD"),
                result("100", "USD", POLICY, AnalysisRecommendation.CONFIRMED, true),
                List.of(new KnowledgeEvidence(wrongDocument, POLICY)));
        assertRejected(guard(), facts("100", "USD"),
                result("100", "USD", POLICY, AnalysisRecommendation.CONFIRMED, true),
                List.of(new KnowledgeEvidence(wrongSection, POLICY)));
    }

    @Test
    void rejectsDuplicateMatchingEvidence() {
        KnowledgeEvidence item = evidence(POLICY, hash(POLICY));
        assertRejected(guard(), facts("100", "USD"),
                result("100", "USD", POLICY, AnalysisRecommendation.CONFIRMED, true),
                List.of(item, item));
    }

    @Test
    void allowsConservativeInsufficientEvidenceWithoutARegisteredPolicyExcerpt() {
        assertThatCode(() -> guard().validate(facts("100", "USD"),
                result("100", "USD", POLICY, AnalysisRecommendation.INSUFFICIENT_EVIDENCE, false),
                List.of())).doesNotThrowAnyException();
    }

    @Test
    void rejectsConflictingRulesForSameRuleCodeAtConfigurationTime() {
        assertThatThrownBy(() -> new NumericThresholdGuard(List.of(
                rule(NumericThresholdGuard.Operator.GT, "1"),
                rule(NumericThresholdGuard.Operator.GTE, "2"))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsUsingCoreRiskTriggerAsAnApprovedReviewDecision() {
        assertThatThrownBy(() -> new NumericThresholdGuard.Rule("v1", "LARGE_AMOUNT", "USD",
                NumericThresholdGuard.Operator.GTE, new BigDecimal("10000.00"), "doc", "v1", "section", hash(POLICY)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void unconfiguredRulesAreExplicitlyOutsideTheGuardCoverage() {
        assertThatCode(() -> new NumericThresholdGuard(List.of()).validate(facts("100", "USD"),
                result("100", "USD", POLICY, AnalysisRecommendation.CONFIRMED, true), List.of()))
                .doesNotThrowAnyException();
    }

    private NumericThresholdGuard guard() {
        return new NumericThresholdGuard(List.of(rule(NumericThresholdGuard.Operator.GT, "0.100000000000000000")));
    }

    private NumericThresholdGuard.Rule rule(NumericThresholdGuard.Operator operator, String threshold) {
        return new NumericThresholdGuard.Rule("numeric-h03-v1", "HO_RULE_03", "USD", operator,
                new BigDecimal(threshold), "HO-P03", "1.0", "threshold", hash(POLICY));
    }

    private void assertRejected(NumericThresholdGuard guard, ReviewTaskContext facts, AnalysisResult result,
            List<KnowledgeEvidence> evidence) {
        assertThatThrownBy(() -> guard.validate(facts, result, evidence))
                .isInstanceOf(CopilotException.class)
                .extracting(exception -> ((CopilotException) exception).code())
                .isEqualTo(ErrorCode.FACT_VALIDATION_FAILED);
    }

    private ReviewTaskContext facts(String amount, String currency) {
        return new ReviewTaskContext(new ReviewTaskContext.ReviewTask(98103, 0, "PENDING", "HO_RULE_03", "HO_ALERT_03", NOW),
                new ReviewTaskContext.Transaction(78103, 68103, new BigDecimal(amount), currency, NOW, "SYNTHETIC"),
                new ReviewTaskContext.Reconciliation("MATCHED", "REFERENCE_ID", "HO_ALERT_03"), List.of(),
                new ReviewTaskContext.AccountSummary("ACTIVE", "masked"), NOW);
    }

    private AnalysisResult result(String amount, String currency, String excerpt,
            AnalysisRecommendation recommendation, boolean cite) {
        return new AnalysisResult(AnalysisResult.CURRENT_SCHEMA_VERSION, ID, "summary", "explanation",
                List.of(new AnalysisResult.BusinessEvidence(78103, 68103, new BigDecimal(amount), currency,
                        "HO_RULE_03", "HO_ALERT_03", List.of("transaction.amount"))),
                cite ? List.of(new AnalysisResult.PolicyCitation("HO-P03", "1.0", "threshold", excerpt)) : List.of(),
                recommendation, "reason", List.of(), List.of());
    }

    private KnowledgeEvidence evidence(String text, String checksum) {
        return new KnowledgeEvidence(new KnowledgeCitation("HO-P03", "1.0", "chunk", "threshold",
                checksum, 0.9, "published-v1"), text);
    }

    private String hash(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
