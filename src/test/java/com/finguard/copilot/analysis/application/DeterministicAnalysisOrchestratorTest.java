package com.finguard.copilot.analysis.application;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.finguard.copilot.analysis.domain.AnalysisRecommendation;
import com.finguard.copilot.analysis.domain.AnalysisResult;
import com.finguard.copilot.context.FactSnapshot;
import com.finguard.copilot.context.SnapshotCodec;
import com.finguard.copilot.coreclient.port.ReviewTaskContext;
import com.finguard.copilot.coreclient.port.ReviewTaskContext.AccountSummary;
import com.finguard.copilot.coreclient.port.ReviewTaskContext.Reconciliation;
import com.finguard.copilot.coreclient.port.ReviewTaskContext.ReviewTask;
import com.finguard.copilot.coreclient.port.ReviewTaskContext.RiskFact;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DeterministicAnalysisOrchestratorTest {

    private static final UUID ANALYSIS_ID = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final Instant CAPTURED_AT = Instant.parse("2026-08-25T01:02:03.456789Z");

    private final FactSnapshot snapshot = new SnapshotCodec().capture(new ReviewTaskContext(
            new ReviewTask(101, 0, "PENDING", "HIGH_AMOUNT", "AMOUNT_THRESHOLD_EXCEEDED",
                    CAPTURED_AT.minusSeconds(60)),
            new ReviewTaskContext.Transaction(202, 303, new BigDecimal("120000.00"), "CNY",
                    CAPTURED_AT.minusSeconds(30), "CSV_IMPORT"),
            new Reconciliation("SUSPICIOUS", "AMOUNT_AND_TIME", "REQUIRES_REVIEW"),
            List.of(new RiskFact("HIGH_AMOUNT", "120000.00", "TRANSACTION")),
            new AccountSummary("ACTIVE", "Primary"),
            CAPTURED_AT));

    @Test
    void createsDeterministicInsufficientEvidenceResultFromSnapshotFacts() {
        AnalysisResult result = new DeterministicAnalysisOrchestrator().analyze(ANALYSIS_ID, snapshot);

        assertThat(result.schemaVersion()).isEqualTo(1);
        assertThat(result.analysisId()).isEqualTo(ANALYSIS_ID);
        assertThat(result.recommendation()).isEqualTo(AnalysisRecommendation.INSUFFICIENT_EVIDENCE);
        assertThat(result.reviewTaskSummary()).contains("101").contains("PENDING");
        assertThat(result.riskRuleExplanation()).contains("HIGH_AMOUNT");
        assertThat(result.businessEvidence()).containsExactly(new AnalysisResult.BusinessEvidence(
                202,
                303,
                new BigDecimal("120000"),
                "CNY",
                "HIGH_AMOUNT",
                "AMOUNT_THRESHOLD_EXCEEDED",
                List.of(
                        "transaction.id",
                        "transaction.accountId",
                        "transaction.amount",
                        "transaction.currency",
                        "reviewTask.ruleCode",
                        "reviewTask.reasonCode")));
        assertThat(result.policyCitations()).isEmpty();
        assertThat(result.missingInformation()).contains("Published policy citation");
        assertThat(result.limitations()).contains("Phase 1 has no published knowledge index");
    }

    @Test
    void producesTheSameResultForTheSameSnapshotAndDoesNotUseModelContent() {
        AnalysisResult first = new DeterministicAnalysisOrchestrator().analyze(ANALYSIS_ID, snapshot);
        AnalysisResult second = new DeterministicAnalysisOrchestrator().analyze(ANALYSIS_ID, snapshot);

        assertThat(first).isEqualTo(second);
        assertThat(first.toString()).doesNotContain("StubModelGateway", "stub:");
    }
}
