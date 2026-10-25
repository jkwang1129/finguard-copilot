package com.finguard.copilot.guardrail;

import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finguard.copilot.analysis.domain.AnalysisResult;
import com.finguard.copilot.common.error.CopilotException;
import com.finguard.copilot.common.error.ErrorCode;

public final class AnalysisOutputSchemaValidator {

    public static final int SCHEMA_VERSION = 1;
    public static final Set<String> REQUIRED_PROPERTIES = Set.of(
            "schemaVersion",
            "analysisId",
            "reviewTaskSummary",
            "riskRuleExplanation",
            "businessEvidence",
            "policyCitations",
            "recommendation",
            "reasoningSummary",
            "missingInformation",
            "limitations");
    public static final Set<String> ALLOWED_PROPERTIES = REQUIRED_PROPERTIES;
    public static final Set<String> RECOMMENDATION_VALUES = Set.of(
            "CONFIRMED", "IGNORED", "INSUFFICIENT_EVIDENCE");

    private static final Set<String> BUSINESS_EVIDENCE_PROPERTIES = Set.of(
            "transactionId", "accountId", "amount", "currency", "ruleCode", "reasonCode", "sourcePaths");
    private static final Set<String> POLICY_CITATION_PROPERTIES = Set.of(
            "documentId", "version", "sourcePath", "excerpt");
    private static final JsonNode PUBLISHED_SCHEMA = loadPublishedSchema();

    private final ObjectMapper mapper;

    public AnalysisOutputSchemaValidator() {
        this(new ObjectMapper());
    }

    public AnalysisOutputSchemaValidator(ObjectMapper mapper) {
        this.mapper = Objects.requireNonNull(mapper, "mapper must not be null")
                .copy()
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
        if (PUBLISHED_SCHEMA.isMissingNode()) {
            throw new IllegalStateException("published analysis output schema is unavailable");
        }
    }

    public AnalysisResult validate(AnalysisResult candidate) {
        Objects.requireNonNull(candidate, "candidate must not be null");
        return validate(mapper.valueToTree(candidate));
    }

    public AnalysisResult validate(JsonNode candidate) {
        try {
            validateShape(candidate);
            return mapper.treeToValue(candidate, AnalysisResult.class);
        } catch (CopilotException exception) {
            throw exception;
        } catch (JsonProcessingException | IllegalArgumentException exception) {
            throw invalid("analysis output cannot be deserialized");
        }
    }

    public AnalysisResult validate(String candidateJson) {
        if (candidateJson == null || candidateJson.isBlank()) {
            throw invalid("analysis output must not be blank");
        }
        try {
            return validate(mapper.readValue(candidateJson, JsonNode.class));
        } catch (IOException exception) {
            throw invalid("analysis output is not valid JSON");
        }
    }

    public AnalysisResult validate(byte[] candidateJson) {
        if (candidateJson == null || candidateJson.length == 0) {
            throw invalid("analysis output must not be empty");
        }
        try {
            return validate(mapper.readValue(candidateJson, JsonNode.class));
        } catch (IOException exception) {
            throw invalid("analysis output is not valid JSON");
        }
    }

    private void validateShape(JsonNode candidate) {
        if (candidate == null || !candidate.isObject()) {
            throw invalid("analysis output must be a JSON object");
        }
        Set<String> actualProperties = new LinkedHashSet<>();
        candidate.fieldNames().forEachRemaining(actualProperties::add);
        if (!actualProperties.equals(REQUIRED_PROPERTIES)) {
            throw invalid("analysis output properties are not the exact version-one set");
        }
        if (!candidate.get("schemaVersion").isInt()
                || candidate.get("schemaVersion").intValue() != SCHEMA_VERSION) {
            throw invalid("analysis output schemaVersion must be 1");
        }
        requireUuid(candidate.get("analysisId"), "analysisId");
        requireText(candidate.get("reviewTaskSummary"), "reviewTaskSummary");
        requireText(candidate.get("riskRuleExplanation"), "riskRuleExplanation");
        requireEvidenceArray(candidate.get("businessEvidence"));
        requirePolicyCitationArray(candidate.get("policyCitations"));
        JsonNode recommendation = candidate.get("recommendation");
        requireText(recommendation, "recommendation");
        if (!RECOMMENDATION_VALUES.contains(recommendation.textValue())) {
            throw invalid("recommendation is not a supported value");
        }
        requireText(candidate.get("reasoningSummary"), "reasoningSummary");
        requireStringArray(candidate.get("missingInformation"), "missingInformation");
        requireStringArray(candidate.get("limitations"), "limitations");
    }

    private void requireEvidenceArray(JsonNode evidence) {
        if (evidence == null || !evidence.isArray() || evidence.isEmpty()) {
            throw invalid("businessEvidence must be a non-empty array");
        }
        for (JsonNode item : evidence) {
            requireExactObject(item, BUSINESS_EVIDENCE_PROPERTIES, "businessEvidence item");
            requirePositiveInteger(item.get("transactionId"), "transactionId");
            requirePositiveInteger(item.get("accountId"), "accountId");
            if (!item.get("amount").isNumber() || item.get("amount").decimalValue().signum() < 0) {
                throw invalid("businessEvidence amount must be a non-negative number");
            }
            requireText(item.get("currency"), "currency");
            requireText(item.get("ruleCode"), "ruleCode");
            requireText(item.get("reasonCode"), "reasonCode");
            requireStringArray(item.get("sourcePaths"), "sourcePaths");
            if (item.get("sourcePaths").isEmpty()) {
                throw invalid("sourcePaths must not be empty");
            }
        }
    }

    private void requirePolicyCitationArray(JsonNode citations) {
        if (citations == null || !citations.isArray()) {
            throw invalid("policyCitations must be an array");
        }
        for (JsonNode item : citations) {
            requireExactObject(item, POLICY_CITATION_PROPERTIES, "policyCitations item");
            requireText(item.get("documentId"), "documentId");
            requireText(item.get("version"), "version");
            requireText(item.get("sourcePath"), "sourcePath");
            requireText(item.get("excerpt"), "excerpt");
        }
    }

    private void requireExactObject(JsonNode node, Set<String> properties, String name) {
        if (node == null || !node.isObject()) {
            throw invalid(name + " must be an object");
        }
        Set<String> actual = new LinkedHashSet<>();
        node.fieldNames().forEachRemaining(actual::add);
        if (!actual.equals(properties)) {
            throw invalid(name + " properties are invalid");
        }
    }

    private void requireStringArray(JsonNode value, String name) {
        if (value == null || !value.isArray()) {
            throw invalid(name + " must be an array of strings");
        }
        for (JsonNode item : value) {
            requireText(item, name + " item");
        }
    }

    private void requirePositiveInteger(JsonNode value, String name) {
        if (value == null || !value.isIntegralNumber() || value.longValue() < 1) {
            throw invalid(name + " must be a positive integer");
        }
    }

    private void requireUuid(JsonNode value, String name) {
        requireText(value, name);
        try {
            UUID.fromString(value.textValue());
        } catch (IllegalArgumentException exception) {
            throw invalid(name + " must be a UUID");
        }
    }

    private void requireText(JsonNode value, String name) {
        if (value == null || !value.isTextual() || value.textValue().isBlank()) {
            throw invalid(name + " must be a non-blank string");
        }
    }

    private static JsonNode loadPublishedSchema() {
        try (InputStream input = AnalysisOutputSchemaValidator.class.getResourceAsStream(
                "/schema/analysis-output-v1.schema.json")) {
            if (input == null) {
                return com.fasterxml.jackson.databind.node.MissingNode.getInstance();
            }
            return new ObjectMapper().readTree(input);
        } catch (IOException exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }

    private static CopilotException invalid(String message) {
        return new CopilotException(ErrorCode.OUTPUT_SCHEMA_INVALID, message);
    }
}
