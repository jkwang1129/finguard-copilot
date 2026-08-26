package com.finguard.copilot.port;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.finguard.copilot.coreclient.port.CurrentUser;
import com.finguard.copilot.coreclient.port.ReviewTaskContext;
import com.finguard.copilot.coreclient.port.ReviewTaskContext.AccountSummary;
import com.finguard.copilot.coreclient.port.ReviewTaskContext.Reconciliation;
import com.finguard.copilot.coreclient.port.ReviewTaskContext.ReviewTask;
import com.finguard.copilot.coreclient.port.ReviewTaskContext.RiskFact;
import com.finguard.copilot.coreclient.port.ReviewTaskContext.Transaction;
import com.finguard.copilot.knowledge.port.EmbeddingVector;
import com.finguard.copilot.model.port.ModelRequest;
import com.finguard.copilot.model.port.ModelResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PortContractTest {

    @Test
    void embeddingVectorProtectsFiniteImmutableValues() {
        List<Double> source = new ArrayList<>(List.of(0.25, -0.5, 1.0));

        EmbeddingVector vector = new EmbeddingVector(source);
        source.set(0, 99.0);

        assertThat(vector.values()).containsExactly(0.25, -0.5, 1.0);
        assertThat(vector.dimension()).isEqualTo(3);
        assertThatThrownBy(() -> vector.values().add(2.0))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> new EmbeddingVector(List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new EmbeddingVector(List.of(1.0, Double.NaN)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new EmbeddingVector(List.of(1.0, Double.POSITIVE_INFINITY)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void modelContractsRejectBlankOrNegativeValues() {
        assertThatThrownBy(() -> new ModelRequest(" ", "instructions", "context"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ModelRequest("request-1", " ", "context"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ModelRequest("request-1", "instructions", " "))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ModelResponse("model", "content", -1, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ModelResponse("model", "content", 0, -1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void coreContractsRetainTypedFactsAndImmutableCollections() {
        Set<String> roles = new HashSet<>(Set.of("REVIEWER"));
        CurrentUser currentUser = new CurrentUser("reviewer-1", roles);
        roles.clear();

        List<RiskFact> riskFacts = new ArrayList<>(List.of(
                new RiskFact("AMOUNT", "120000.00", "transaction.amount")
        ));
        ReviewTaskContext context = new ReviewTaskContext(
                new ReviewTask(
                        1001L,
                        3L,
                        "PENDING",
                        "HIGH_AMOUNT",
                        "AMOUNT_THRESHOLD_EXCEEDED",
                        Instant.parse("2026-08-20T08:00:00Z")
                ),
                new Transaction(
                        2001L,
                        3001L,
                        new BigDecimal("120000.00"),
                        "CNY",
                        Instant.parse("2026-08-20T07:55:00Z"),
                        "MANUAL"
                ),
                new Reconciliation(
                        "SUSPICIOUS",
                        "AMOUNT_AND_TIME",
                        "DUPLICATE_CANDIDATE"
                ),
                riskFacts,
                new AccountSummary("ACTIVE", "王**"),
                Instant.parse("2026-08-20T08:01:00Z")
        );
        riskFacts.clear();

        assertThat(currentUser.roles()).containsExactly("REVIEWER");
        assertThatThrownBy(() -> currentUser.roles().add("ADMIN"))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThat(context.reviewTask().version()).isEqualTo(3L);
        assertThat(context.transaction().amount()).isEqualByComparingTo("120000.00");
        assertThat(context.transaction().amount().scale()).isEqualTo(2);
        assertThat(context.riskFacts()).hasSize(1);
        assertThatThrownBy(() -> context.riskFacts().clear())
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void reviewTaskAllowsCoreVersionZero() {
        ReviewTask reviewTask = new ReviewTask(
                1001L,
                0L,
                "PENDING",
                "HIGH_AMOUNT",
                "AMOUNT_THRESHOLD_EXCEEDED",
                Instant.parse("2026-08-20T08:00:00Z")
        );

        assertThat(reviewTask.version()).isZero();
    }

    @Test
    void reviewTaskAllowsNullableRuleCodeForReconciliationException() {
        ReviewTask reviewTask = new ReviewTask(
                1001L,
                3L,
                "PENDING",
                null,
                "RECONCILIATION_EXCEPTION",
                Instant.parse("2026-08-20T08:00:00Z")
        );

        assertThat(reviewTask.ruleCode()).isNull();
    }

    @Test
    void reviewTaskRejectsNegativeVersionAndBlankRuleCode() {
        assertThatThrownBy(() -> new ReviewTask(
                1001L,
                -1L,
                "PENDING",
                "HIGH_AMOUNT",
                "AMOUNT_THRESHOLD_EXCEEDED",
                Instant.parse("2026-08-20T08:00:00Z")
        )).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ReviewTask(
                1001L,
                3L,
                "PENDING",
                " ",
                "RECONCILIATION_EXCEPTION",
                Instant.parse("2026-08-20T08:00:00Z")
        )).isInstanceOf(IllegalArgumentException.class);
    }
}
