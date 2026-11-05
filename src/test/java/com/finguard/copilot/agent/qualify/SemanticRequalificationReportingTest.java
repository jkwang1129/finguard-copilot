package com.finguard.copilot.agent.qualify;
import java.math.BigDecimal;
import java.time.Duration;
import com.finguard.copilot.analysis.application.*;
import com.finguard.copilot.common.error.ErrorCode;
import com.finguard.copilot.model.port.ModelUsage;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class SemanticRequalificationReportingTest {
    @Test void refusesTwelfthAttemptAndOverCapBeforeProviderSend() {
        assertDoesNotThrow(() -> SemanticRequalificationReporting.requireSendWithinBudget(10,new BigDecimal("1.10")));
        assertThrows(RuntimeException.class, () -> SemanticRequalificationReporting.requireSendWithinBudget(11,new BigDecimal("0.01")));
        assertThrows(RuntimeException.class, () -> SemanticRequalificationReporting.requireSendWithinBudget(0,new BigDecimal("1.10000001")));
        assertThrows(RuntimeException.class, () -> SemanticRequalificationReporting.requireSendWithinBudget(0,null));
    }
    @Test void reportsOnlyClosedStageValuesAndFinalFactBoundary() {
        for (var stage : ModelValidationStage.values()) {
            var failure = new ModelInvocationException(ErrorCode.FACT_VALIDATION_FAILED,"provider","model","prompt",
                new ModelUsage(12,3,Duration.ofMillis(1)),"FAILURE",stage);
            assertEquals(stage.name(),SemanticRequalificationReporting.failureStage(failure,false));
        }
        var unsafe = new RuntimeException("unsafe candidate; api-key secret");
        assertEquals("UNKNOWN",SemanticRequalificationReporting.failureStage(unsafe,false));
        assertEquals("FINAL_FACTS",SemanticRequalificationReporting.failureStage(unsafe,true));
    }
    @Test void readinessIsAcceptedOnlyWithOriginalIdentityAndZeroHoldoutExecution() throws Exception {
        var value = SemanticRagPreparationData.JSON.createObjectNode();
        value.put("status","REQUALIFICATION_PREPARED");value.put("publicationReadinessOnly",true);
        value.put("holdoutExecutions",0);value.put("holdoutQualityPassed",false);
        value.put("originalPreparationSha256","c66bac3d56a163141b0aee29460cc00cfb549fa9bfd625cf53a82a9c04c9b386");
        value.put("corpusSha256","9fdd43cb211ec5a230f4deaaaf6e0c1d1a7bbc6b8b47e61b82e59e21698fe982");
        value.put("minimumScore",0.8569634500000001);
        assertTrue(SemanticRequalificationReporting.acceptsPreparation(value));
        value.put("holdoutExecutions",1);assertFalse(SemanticRequalificationReporting.acceptsPreparation(value));
        value.put("holdoutExecutions",0);value.put("minimumScore",0.8);
        assertFalse(SemanticRequalificationReporting.acceptsPreparation(value));
    }
}
