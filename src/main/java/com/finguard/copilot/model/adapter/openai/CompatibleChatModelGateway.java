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
    private final java.util.function.Consumer<com.finguard.copilot.model.port.ProviderCallEvidence> evidence;

    public CompatibleChatModelGateway(CopilotProperties.Model model, RestClient.Builder restClientBuilder) {
        this(model,restClientBuilder,ignored->{});
    }
    public CompatibleChatModelGateway(CopilotProperties.Model model,RestClient.Builder restClientBuilder,
            java.util.function.Consumer<com.finguard.copilot.model.port.ProviderCallEvidence> evidence) {
        this.model = Objects.requireNonNull(model, "model must not be null");
        if (!model.apiKey().isPresent()) {
            throw new IllegalArgumentException("model api key must be configured");
        }
        if (model.baseUrl() == null || !model.isSafeBaseUrl()) {
            throw new IllegalArgumentException("model base URL must be safe");
        }
        this.objectMapper = new ObjectMapper();
        this.evidence=Objects.requireNonNull(evidence);
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
            var entity = restClient.post()
                    .uri(model.chatCompletionsPath())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(payload)
                    .retrieve()
                    .toEntity(String.class);
            String responseBody=entity.getBody();
            observe(request,responseBody,entity.getHeaders(),entity.getStatusCode().value(),started);
            return response(objectMapper.readValue(responseBody, OpenAiCompatibleResponse.class),
                    Duration.between(started, Instant.now()));
        } catch (RestClientResponseException exception) {
            observe(request,exception.getResponseBodyAsString(),exception.getResponseHeaders(),exception.getStatusCode().value(),started);
            throw upstreamFailure(exception.getStatusCode().value());
        } catch (ResourceAccessException exception) {
            throw new CopilotException(ErrorCode.MODEL_TIMEOUT, "model provider timed out");
        } catch (RestClientException | JsonProcessingException | IllegalArgumentException exception) {
            throw invalidOrUnavailable(exception);
        }
    }

    private void observe(ModelRequest request,String body,HttpHeaders headers,int status,Instant started) {
        JsonNode parsed=objectMapper.createObjectNode();
        if(body!=null) try {parsed=objectMapper.readTree(body);} catch(JsonProcessingException ignored) { /* Metadata may be unavailable. */ }
        if(parsed==null) parsed=objectMapper.createObjectNode();
        String providerId=parsed.path("id").isTextual()?parsed.path("id").asText():null;
        if(providerId==null && headers!=null) providerId=headers.getFirst("x-request-id");
        String returnedModel=parsed.path("model").isTextual()?parsed.path("model").asText():null;
        JsonNode input=parsed.path("usage").path("prompt_tokens"),output=parsed.path("usage").path("completion_tokens");
        evidence.accept(new com.finguard.copilot.model.port.ProviderCallEvidence(request.requestId(),model.provider(),metadataHash(providerId),metadataHash(returnedModel),
                status,input.isIntegralNumber()&&input.canConvertToLong()?input.longValue():null,
                output.isIntegralNumber()&&output.canConvertToLong()?output.longValue():null,Math.max(0,Duration.between(started,Instant.now()).toMillis())));
    }
    private String metadataHash(String value) {
        if(value==null || value.isBlank() || value.length()>512) return null;
        try {return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));}
        catch(java.security.NoSuchAlgorithmException e){throw new IllegalStateException(e);}
    }

    private JsonNode requestPayload(ModelRequest request) throws JsonProcessingException {
        return CompatibleChatRequestPayload.create(model,request);
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
