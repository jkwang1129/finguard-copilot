package com.finguard.copilot.guardrail;

import java.io.IOException;
import java.io.InputStream;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.finguard.copilot.analysis.domain.AnalysisResult;
import com.finguard.copilot.common.error.CopilotException;
import com.finguard.copilot.common.error.ErrorCode;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AnalysisOutputSchemaValidatorTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final AnalysisOutputSchemaValidator validator = new AnalysisOutputSchemaValidator(mapper);

    @Test
    void publishedSchemaMatchesExecutableValidatorConstants() throws IOException {
        JsonNode schema;
        try (InputStream input = getClass().getResourceAsStream(
                "/schema/analysis-output-v1.schema.json")) {
            assertThat(input).as("versioned analysis output schema").isNotNull();
            schema = mapper.readTree(input);
        }

        assertThat(schema.get("required").valueStream().map(JsonNode::asText).toList())
                .containsExactlyInAnyOrderElementsOf(AnalysisOutputSchemaValidator.REQUIRED_PROPERTIES);
        assertThat(schema.get("properties").fieldNames())
                .toIterable()
                .containsExactlyInAnyOrderElementsOf(AnalysisOutputSchemaValidator.ALLOWED_PROPERTIES);
        assertThat(schema.get("properties").get("recommendation").get("enum")
                .valueStream().map(JsonNode::asText).toList())
                .containsExactlyInAnyOrderElementsOf(AnalysisOutputSchemaValidator.RECOMMENDATION_VALUES);
        assertThat(schema.get("additionalProperties").asBoolean()).isFalse();
    }

    @Test
    void rejectsUnknownTopLevelPropertyWithBoundedSchemaCode() throws Exception {
        ObjectNode candidate = (ObjectNode) mapper.readTree(validJson());
        candidate.put("untrusted", true);

        assertThatThrownBy(() -> validator.validate(candidate))
                .isInstanceOf(CopilotException.class)
                .extracting(exception -> ((CopilotException) exception).code())
                .isEqualTo(ErrorCode.OUTPUT_SCHEMA_INVALID);
    }

    @Test
    void acceptsTheExactPhaseOneOutputShape() throws Exception {
        AnalysisResult result = validator.validate(mapper.readTree(validJson()));

        assertThat(result.schemaVersion()).isEqualTo(1);
        assertThat(result.businessEvidence()).hasSize(1);
        assertThat(result.policyCitations()).isEmpty();
    }

    @Test
    void rejectsUnknownNestedPropertyWithBoundedSchemaCode() throws Exception {
        ObjectNode candidate = (ObjectNode) mapper.readTree(validJson());
        ((ObjectNode) candidate.get("businessEvidence").get(0)).put("untrusted", true);

        assertSchemaFailure(candidate);
    }

    @Test
    void rejectsMissingNestedPropertyWithBoundedSchemaCode() throws Exception {
        ObjectNode candidate = (ObjectNode) mapper.readTree(validJson());
        ((ObjectNode) candidate.get("businessEvidence").get(0)).remove("currency");

        assertSchemaFailure(candidate);
    }

    @Test
    void rejectsWrongScalarAndArrayTypesWithBoundedSchemaCode() throws Exception {
        ObjectNode wrongScalar = (ObjectNode) mapper.readTree(validJson());
        ((ObjectNode) wrongScalar.get("businessEvidence").get(0)).put("transactionId", "202");
        assertSchemaFailure(wrongScalar);

        ObjectNode wrongArray = (ObjectNode) mapper.readTree(validJson());
        wrongArray.put("missingInformation", "Published policy citation");
        assertSchemaFailure(wrongArray);
    }

    @Test
    void rejectsInvalidEnumVersionAndUuidWithBoundedSchemaCode() throws Exception {
        ObjectNode invalidEnum = (ObjectNode) mapper.readTree(validJson());
        invalidEnum.put("recommendation", "MAYBE");
        assertSchemaFailure(invalidEnum);

        ObjectNode invalidVersion = (ObjectNode) mapper.readTree(validJson());
        invalidVersion.put("schemaVersion", 2);
        assertSchemaFailure(invalidVersion);

        ObjectNode invalidUuid = (ObjectNode) mapper.readTree(validJson());
        invalidUuid.put("analysisId", "not-a-uuid");
        assertSchemaFailure(invalidUuid);
    }

    @Test
    void rejectsTrailingJsonTokensWithBoundedSchemaCode() {
        assertThatThrownBy(() -> validator.validate(validJson() + "{}"))
                .isInstanceOf(CopilotException.class)
                .extracting(exception -> ((CopilotException) exception).code())
                .isEqualTo(ErrorCode.OUTPUT_SCHEMA_INVALID);
    }

    private void assertSchemaFailure(JsonNode candidate) {
        assertThatThrownBy(() -> validator.validate(candidate))
                .isInstanceOf(CopilotException.class)
                .extracting(exception -> ((CopilotException) exception).code())
                .isEqualTo(ErrorCode.OUTPUT_SCHEMA_INVALID);
    }

    private static String validJson() {
        return """
                {
                  "schemaVersion": 1,
                  "analysisId": "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
                  "reviewTaskSummary": "Review task 101 is PENDING.",
                  "riskRuleExplanation": "Rule HIGH_AMOUNT was recorded.",
                  "businessEvidence": [{
                    "transactionId": 202,
                    "accountId": 303,
                    "amount": 120000,
                    "currency": "CNY",
                    "ruleCode": "HIGH_AMOUNT",
                    "reasonCode": "AMOUNT_THRESHOLD_EXCEEDED",
                    "sourcePaths": ["transaction.id"]
                  }],
                  "policyCitations": [],
                  "recommendation": "INSUFFICIENT_EVIDENCE",
                  "reasoningSummary": "There is no published policy citation.",
                  "missingInformation": ["Published policy citation"],
                  "limitations": ["Phase 1 has no published knowledge index"]
                }
                """;
    }
}
