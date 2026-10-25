package com.finguard.copilot.model.validation;

import java.util.Locale;
import java.util.Objects;
import java.util.Set;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finguard.copilot.analysis.domain.AnalysisResult;
import com.finguard.copilot.common.error.CopilotException;
import com.finguard.copilot.common.error.ErrorCode;
import com.finguard.copilot.guardrail.AnalysisOutputSchemaValidator;

public final class StructuredAnalysisParser {

    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(StructuredAnalysisParser.class);

    private static final Set<String> INJECTION_MARKERS = Set.of(
            "ignore system rules",
            "ignore previous instructions",
            "ignore all instructions",
            "call arbitrary url",
            "访问任意 url",
            "调用任意 url",
            "忽略系统规则",
            "忽略之前的指令");

    private final ObjectMapper mapper;
    private final AnalysisOutputSchemaValidator schemaValidator;

    public StructuredAnalysisParser() {
        this(new ObjectMapper(), new AnalysisOutputSchemaValidator());
    }

    public StructuredAnalysisParser(ObjectMapper mapper, AnalysisOutputSchemaValidator schemaValidator) {
        this.mapper = Objects.requireNonNull(mapper, "mapper must not be null")
                .copy()
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
        this.schemaValidator = Objects.requireNonNull(schemaValidator, "schemaValidator must not be null");
    }

    public AnalysisResult parse(String content) {
        if (content == null || content.isBlank()) {
            throw invalid("model output must not be blank");
        }
        try {
            String json = unwrapCodeFence(content);
            JsonNode node = mapper.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                    .readTree(json);
            rejectInjection(node);
            return schemaValidator.validate(node);
        } catch (CopilotException exception) {
            LOG.warn("Structured model output rejected: code={}, reason={}", exception.code(), exception.getMessage());
            throw invalid("model output failed validation");
        } catch (JsonProcessingException | IllegalArgumentException exception) {
            LOG.warn("Structured model JSON parse rejected: type={}", exception.getClass().getSimpleName());
            throw invalid("model output is not valid structured JSON");
        }
    }

    private String unwrapCodeFence(String content) {
        String trimmed = content.trim();
        if (!trimmed.startsWith("```") || !trimmed.endsWith("```")) {
            return trimmed;
        }
        int firstLineEnd = trimmed.indexOf('\n');
        if (firstLineEnd < 0) {
            throw invalid("model output code fence is malformed");
        }
        String language = trimmed.substring(3, firstLineEnd).trim();
        if (!language.isEmpty() && !language.equalsIgnoreCase("json")) {
            throw invalid("model output code fence language is not supported");
        }
        return trimmed.substring(firstLineEnd + 1, trimmed.length() - 3).trim();
    }

    private void rejectInjection(JsonNode node) {
        if (node == null) {
            throw invalid("model output is empty");
        }
        if (node.isTextual()) {
            String value = node.textValue().toLowerCase(Locale.ROOT);
            if (INJECTION_MARKERS.stream().anyMatch(value::contains)) {
                throw invalid("model output contains an instruction marker");
            }
            return;
        }
        if (node.isContainerNode()) {
            node.elements().forEachRemaining(this::rejectInjection);
        }
    }

    private static CopilotException invalid(String message) {
        return new CopilotException(ErrorCode.MODEL_OUTPUT_INVALID, message);
    }
}
