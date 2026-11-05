package com.finguard.copilot.agent.qualify;
import java.math.BigDecimal;
import com.fasterxml.jackson.databind.JsonNode;
import com.finguard.copilot.analysis.application.*;
import com.finguard.copilot.governance.RunManifestService;
final class SemanticRequalificationReporting {
    static void requireSendWithinBudget(int attempts, BigDecimal exposure) {
        if (attempts < 0 || attempts >= 11 || exposure == null || exposure.signum() < 0
                || exposure.compareTo(new BigDecimal("1.10")) > 0)
            throw RunManifestService.rejected("Component request or exposure cap invalid");
    }
    static String failureStage(Exception failure, boolean finalFacts) {
        if (finalFacts) return ModelValidationStage.FINAL_FACTS.name();
        return failure instanceof ModelInvocationException invocation ? invocation.validationStage().name() : ModelValidationStage.UNKNOWN.name();
    }
    static boolean acceptsPreparation(JsonNode report) {
        if ("RAG_EVALUATED".equals(report.path("status").asText())) return true;
        return "REQUALIFICATION_PREPARED".equals(report.path("status").asText())
            && report.path("publicationReadinessOnly").asBoolean(false)
            && report.path("holdoutExecutions").isIntegralNumber() && report.path("holdoutExecutions").intValue() == 0
            && report.path("holdoutQualityPassed").isBoolean() && !report.path("holdoutQualityPassed").booleanValue()
            && SemanticRequalificationPreparationData.ORIGINAL_SHA.equals(report.path("originalPreparationSha256").asText())
            && SemanticRequalificationPreparationData.CORPUS_SHA.equals(report.path("corpusSha256").asText())
            && report.path("minimumScore").isNumber()
            && report.path("minimumScore").doubleValue() == SemanticRequalificationPreparationData.MINIMUM_SCORE;
    }
}
