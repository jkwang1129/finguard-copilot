package com.finguard.copilot.model.adapter.openai;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.finguard.copilot.common.error.CopilotException;
import com.finguard.copilot.common.error.ErrorCode;
import com.finguard.copilot.config.CopilotProperties;
import com.finguard.copilot.model.port.ModelGateway;
import com.finguard.copilot.model.port.ModelRequest;
import com.finguard.copilot.model.port.ModelResponse;
import com.finguard.copilot.model.port.ModelUsage;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.ResourceAccessException;

public final class CompatibleChatModelGateway implements ModelGateway {

    private final CopilotProperties.Model model;
    private final ObjectMapper objectMapper;
    private final RestClient restClient;

    public CompatibleChatModelGateway(CopilotProperties.Model model, RestClient.Builder restClientBuilder) {
        this.model = Objects.requireNonNull(model, "model must not be null");
        if (!model.apiKey().isPresent()) {
            throw new IllegalArgumentException("model api key must be configured");
        }
        if (model.baseUrl() == null || !model.isSafeBaseUrl()) {
            throw new IllegalArgumentException("model base URL must be safe");
        }
        this.objectMapper = new ObjectMapper();
        this.restClient = Objects.requireNonNull(restClientBuilder, "restClientBuilder must not be null")
                .baseUrl(model.baseUrl().toString())
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + model.apiKey().value())
                .build();
    }

    @Override
    public ModelResponse complete(ModelRequest request) {
        Objects.requireNonNull(request, "request must not be null");
        Instant started = Instant.now();
        try {
            JsonNode payload = requestPayload(request);
            String responseBody = restClient.post()
                    .uri(model.chatCompletionsPath())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(payload)
                    .retrieve()
                    .body(String.class);
            return response(objectMapper.readValue(responseBody, OpenAiCompatibleResponse.class),
                    Duration.between(started, Instant.now()));
        } catch (RestClientResponseException exception) {
            throw upstreamFailure(exception.getStatusCode().value());
        } catch (ResourceAccessException exception) {
            throw new CopilotException(ErrorCode.MODEL_TIMEOUT, "model provider timed out");
        } catch (RestClientException | JsonProcessingException | IllegalArgumentException exception) {
            throw invalidOrUnavailable(exception);
        }
    }

    private JsonNode requestPayload(ModelRequest request) throws JsonProcessingException {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("model", model.modelId());
        ArrayNode messages = payload.putArray("messages");
        messages.addObject().put("role", "system").put("content",
                request.systemInstruction() + "\nOUTPUT_SCHEMA\n" + request.outputSchemaJson());
        messages.addObject().put("role", "user").put("content", request.context());
        payload.put("max_tokens", Math.min(request.maxOutputTokens(), model.outputTokenLimit()));
        payload.put("temperature", effectiveTemperature(request.temperature()));
        if ("deepseek".equalsIgnoreCase(model.provider())) {
            payload.putObject("thinking").put("type", "disabled");
        }

        ObjectNode responseFormat = payload.putObject("response_format");
        if ("qwen".equalsIgnoreCase(model.provider()) || "deepseek".equalsIgnoreCase(model.provider())) {
            responseFormat.put("type", "json_object");
        } else {
            responseFormat.put("type", "json_schema");
            ObjectNode jsonSchema = responseFormat.putObject("json_schema");
            jsonSchema.put("name", "review_analysis");
            jsonSchema.set("schema", objectMapper.readTree(request.outputSchemaJson()));
        }
        return payload;
    }

    private double effectiveTemperature(double requested) {
        if ("zhipu".equalsIgnoreCase(model.provider()) && requested == 0d) {
            return 0.01d;
        }
        return requested;
    }

    private ModelResponse response(OpenAiCompatibleResponse response, Duration latency) {
        if (response == null || response.choices() == null || response.choices().isEmpty()
                || response.choices().get(0) == null || response.choices().get(0).message() == null
                || response.usage() == null) {
            throw invalidOutput("empty model response");
        }
        String content = response.choices().get(0).message().content();
        Integer inputTokens = response.usage().inputTokens();
        Integer outputTokens = response.usage().outputTokens();
        if (content == null || content.isBlank() || inputTokens == null || outputTokens == null) {
            throw invalidOutput("model response shape is invalid");
        }

        try {
            ModelUsage modelUsage = new ModelUsage(inputTokens, outputTokens, latency);
            return new ModelResponse(model.modelId(), content, inputTokens, outputTokens,
                    content, modelUsage, latency, model.provider());
        } catch (IllegalArgumentException exception) {
            throw invalidOutput("model usage is invalid");
        }
    }

    private CopilotException upstreamFailure(int status) {
        ErrorCode code = status == 429 ? ErrorCode.MODEL_RATE_LIMITED
                : (status == 408 || status == 504 ? ErrorCode.MODEL_TIMEOUT : ErrorCode.MODEL_UNAVAILABLE);
        return new CopilotException(code, "model provider request failed (status " + status + ")");
    }

    private CopilotException invalidOrUnavailable(Exception exception) {
        if (exception instanceof JsonProcessingException || exception instanceof IllegalArgumentException) {
            return invalidOutput("model request or response is invalid");
        }
        return new CopilotException(ErrorCode.MODEL_UNAVAILABLE, "model provider unavailable");
    }

    private CopilotException invalidOutput(String reason) {
        return new CopilotException(ErrorCode.MODEL_OUTPUT_INVALID, reason);
    }
}
