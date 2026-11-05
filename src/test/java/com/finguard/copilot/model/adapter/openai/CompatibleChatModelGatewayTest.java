package com.finguard.copilot.model.adapter.openai;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

import com.finguard.copilot.common.error.CopilotException;
import com.finguard.copilot.common.error.ErrorCode;
import com.finguard.copilot.config.CopilotProperties;
import com.finguard.copilot.config.SecretValue;
import com.finguard.copilot.model.port.ModelRequest;
import com.finguard.copilot.model.port.ModelResponse;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.http.HttpStatus.TOO_MANY_REQUESTS;
import static org.springframework.http.HttpStatus.UNAUTHORIZED;
import static org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class CompatibleChatModelGatewayTest {

    @Test void capturesOnlyHashedProviderCorrelationAndReportedUsage() {
        var builder=RestClient.builder();var server=MockRestServiceServer.bindTo(builder).build();
        var captured=new java.util.concurrent.atomic.AtomicReference<com.finguard.copilot.model.port.ProviderCallEvidence>();
        var gateway=new CompatibleChatModelGateway(model("deepseek","deepseek-flash"),builder,captured::set);
        server.expect(requestTo("https://model.example.internal/v1/chat/completions")).andRespond(withSuccess("""
                {"id":"provider-id-1","model":"provider-model-v1","choices":[{"message":{"content":"{}"}}],"usage":{"prompt_tokens":10,"completion_tokens":5}}
                """,MediaType.APPLICATION_JSON));
        gateway.complete(request());server.verify();
        assertThat(captured.get().providerRequestIdSha256()).isEqualTo(com.finguard.copilot.governance.CanonicalJson.sha256("provider-id-1"));
        assertThat(captured.get().reportedInputTokens()).isEqualTo(10);
        assertThat(captured.get().toString()).doesNotContain("provider-id-1","provider-model-v1","secret-for-test-only","system policy");
    }

    @Test void capturesErrorResponseCorrelationWithoutRetryOrRawBody() {
        var builder=RestClient.builder();var server=MockRestServiceServer.bindTo(builder).build();
        var captured=new java.util.concurrent.atomic.AtomicReference<com.finguard.copilot.model.port.ProviderCallEvidence>();
        var gateway=new CompatibleChatModelGateway(model("deepseek","deepseek-flash"),builder,captured::set);
        server.expect(requestTo("https://model.example.internal/v1/chat/completions")).andRespond(withStatus(TOO_MANY_REQUESTS)
                .header("x-request-id","provider-error-1").body("private upstream error"));
        assertThatThrownBy(()->gateway.complete(request())).isInstanceOf(CopilotException.class);server.verify();
        assertThat(captured.get().httpStatus()).isEqualTo(429);
        assertThat(captured.get().providerRequestIdSha256()).isEqualTo(com.finguard.copilot.governance.CanonicalJson.sha256("provider-error-1"));
        assertThat(captured.get().toString()).doesNotContain("private upstream error","provider-error-1");
    }

    @Test
    void configuredTimeoutStopsSlowLocalModelResponse() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            try {
                TimeUnit.MILLISECONDS.sleep(300);
                byte[] body = """
                        {"choices":[{"message":{"content":"{}"}}],"usage":{"prompt_tokens":1,"completion_tokens":1}}
                        """.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        });
        server.start();
        try {
            CopilotProperties.Model slowModel = new CopilotProperties.Model(
                    "qwen", "qwen-plus",
                    URI.create("http://127.0.0.1:" + server.getAddress().getPort()),
                    new SecretValue("secret-for-test-only"), 32000, 2000, Duration.ofMillis(50),
                    "phase3-2026-08");
            CompatibleChatModelGateway gateway = new CompatibleChatModelGateway(slowModel,
                    RestClient.builder().requestFactory(
                            com.finguard.copilot.model.config.RealModelConfiguration
                                    .modelRequestFactory(slowModel.timeout())));

            assertThatThrownBy(() -> gateway.complete(request()))
                    .isInstanceOfSatisfying(CopilotException.class,
                            exception -> assertThat(exception.code()).isEqualTo(ErrorCode.MODEL_TIMEOUT));
        } finally {
            server.stop(0);
        }
    }

    @ParameterizedTest
    @CsvSource({
            "qwen, qwen-plus",
            "deepseek, deepseek-flash"
    })
    void postsFixedOpenAiCompatibleRequestAndMapsUsage(String provider, String modelId) {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        CompatibleChatModelGateway gateway = new CompatibleChatModelGateway(model(provider, modelId), builder);

        server.expect(requestTo("https://model.example.internal/v1/chat/completions"))
                .andExpect(method(org.springframework.http.HttpMethod.POST))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer secret-for-test-only"))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.model").value(modelId))
                .andExpect(jsonPath("$.messages[0].role").value("system"))
                .andExpect(jsonPath("$.messages[0].content").value("system policy\nOUTPUT_SCHEMA\n{\"type\":\"object\"}"))
                .andExpect(jsonPath("$.messages[1].role").value("user"))
                .andExpect(jsonPath("$.messages[1].content").value("immutable facts"))
                .andExpect(jsonPath("$.max_tokens").value(2000))
                .andExpect(jsonPath("$.temperature").value(0))
                .andExpect(jsonPath("$.response_format.type").value("json_object"))
                .andExpect(clientRequest -> {
                    if ("deepseek".equals(provider)) {
                        jsonPath("$.thinking.type").value("disabled").match(clientRequest);
                    }
                })
                .andRespond(withSuccess("""
                        {
                          "id": "chatcmpl-test",
                          "choices": [{"message": {"content": "{\\"recommendation\\":\\"INSUFFICIENT_EVIDENCE\\"}"}}],
                          "usage": {"prompt_tokens": 12, "completion_tokens": 4, "total_tokens": 16}
                        }
                        """, MediaType.APPLICATION_JSON));

        ModelResponse response = gateway.complete(new ModelRequest(
                "request-1", "system policy", "immutable facts", "{\"type\":\"object\"}", 2000, 0d,
                "review-analysis-v1", "model-config-v1"));

        server.verify();
        assertThat(response.modelId()).isEqualTo(modelId);
        assertThat(response.provider()).isEqualTo(provider);
        assertThat(response.content()).isEqualTo("{\"recommendation\":\"INSUFFICIENT_EVIDENCE\"}");
        assertThat(response.structuredContent()).isEqualTo(response.content());
        assertThat(response.usage().inputTokens()).isEqualTo(12);
        assertThat(response.usage().outputTokens()).isEqualTo(4);
        assertThat(response.usage().latency()).isGreaterThanOrEqualTo(Duration.ZERO);
    }

    @Test
    void postsZhipuRequestToTheProviderSpecificChatCompletionsPath() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        CompatibleChatModelGateway gateway = new CompatibleChatModelGateway(
                model("zhipu", "glm-test", URI.create("https://model.example.internal/api/paas/v4/")), builder);

        server.expect(requestTo("https://model.example.internal/api/paas/v4/chat/completions"))
                .andExpect(method(org.springframework.http.HttpMethod.POST))
                .andExpect(jsonPath("$.temperature").value(0.01))
                .andExpect(jsonPath("$.response_format.type").value("json_schema"))
                .andExpect(jsonPath("$.response_format.json_schema.schema.type").value("object"))
                .andRespond(withSuccess("""
                        {
                          "choices": [{"message": {"content": "{\\"recommendation\\":\\"INSUFFICIENT_EVIDENCE\\"}"}}],
                          "usage": {"prompt_tokens": 12, "completion_tokens": 4}
                        }
                        """, MediaType.APPLICATION_JSON));

        gateway.complete(request());

        server.verify();
    }

    @Test
    void qualifiedBackupUsesTheEvaluatedNonThinkingProtocolAndOneRequest() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        var model = model("zhipu", "glm-5.2", URI.create("https://open.bigmodel.cn/api/paas/v4"));
        var gateway = new CompatibleChatModelGateway(model, builder);
        server.expect(requestTo("https://open.bigmodel.cn/api/paas/v4/chat/completions"))
                .andExpect(jsonPath("$.thinking.type").value("disabled"))
                .andExpect(jsonPath("$.temperature").value(0.01))
                .andExpect(jsonPath("$.response_format.type").value("json_object"))
                .andRespond(org.springframework.test.web.client.response.MockRestResponseCreators.withStatus(
                        org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE));
        assertThatThrownBy(() -> gateway.complete(request())).isInstanceOfSatisfying(CopilotException.class,
                error -> assertThat(error.code()).isEqualTo(ErrorCode.MODEL_UNAVAILABLE));
        server.verify();
    }

    @Test
    void doesNotOfferOrExecuteModelSuppliedArbitraryTools() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        CompatibleChatModelGateway gateway = new CompatibleChatModelGateway(model(), builder);
        server.expect(requestTo("https://model.example.internal/v1/chat/completions"))
                .andExpect(method(org.springframework.http.HttpMethod.POST))
                .andExpect(jsonPath("$.tools").doesNotExist())
                .andRespond(withSuccess("""
                        {"choices":[{"message":{"content":null,"tool_calls":[{"type":"function",
                        "function":{"name":"http_get","arguments":"{\\"url\\":\\"http://127.0.0.1/admin\\"}"}}]}}],
                        "usage":{"prompt_tokens":10,"completion_tokens":3}}
                        """, MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> gateway.complete(request()))
                .isInstanceOfSatisfying(CopilotException.class,
                        exception -> assertThat(exception.code()).isEqualTo(ErrorCode.MODEL_OUTPUT_INVALID));
        server.verify();
    }

    @ParameterizedTest
    @ValueSource(strings = {"\"tool_calls\":[{\"type\":\"function\",\"function\":{\"name\":\"http_get\",\"arguments\":\"{}\"}}]",
            "\"function_call\":{\"name\":\"http_get\",\"arguments\":\"{}\"}"})
    void rejectsToolCallsEvenWhenMessageAlsoContainsText(String extra) {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        CompatibleChatModelGateway gateway = new CompatibleChatModelGateway(model(), builder);
        server.expect(requestTo("https://model.example.internal/v1/chat/completions"))
                .andRespond(withSuccess("{\"choices\":[{\"message\":{\"content\":\"{}\"," + extra
                        + "}}],\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":3}}", MediaType.APPLICATION_JSON));
        assertThatThrownBy(() -> gateway.complete(request())).isInstanceOfSatisfying(CopilotException.class,
                exception -> assertThat(exception.code()).isEqualTo(ErrorCode.MODEL_OUTPUT_INVALID));
        server.verify();
    }

    @ParameterizedTest
    @ValueSource(ints = {401, 500})
    void mapsUpstreamAvailabilityErrorsWithoutReturningUpstreamBody(int status) {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        CompatibleChatModelGateway gateway = new CompatibleChatModelGateway(model(), builder);
        server.expect(requestTo("https://model.example.internal/v1/chat/completions"))
                .andRespond(withStatus(org.springframework.http.HttpStatus.valueOf(status))
                        .body("upstream-secret-provider-response"));

        assertThatThrownBy(() -> gateway.complete(request()))
                .isInstanceOf(CopilotException.class)
                .satisfies(error -> {
                    CopilotException exception = (CopilotException) error;
                    assertThat(exception.code()).isEqualTo(ErrorCode.MODEL_UNAVAILABLE);
                    assertThat(exception.getMessage()).doesNotContain("upstream-secret-provider-response");
                });
        server.verify();
    }

    @Test
    void mapsRateLimitWithoutReturningUpstreamBody() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        CompatibleChatModelGateway gateway = new CompatibleChatModelGateway(model(), builder);
        server.expect(requestTo("https://model.example.internal/v1/chat/completions"))
                .andRespond(withStatus(org.springframework.http.HttpStatus.TOO_MANY_REQUESTS)
                        .body("upstream-secret-provider-response"));

        assertThatThrownBy(() -> gateway.complete(request()))
                .isInstanceOfSatisfying(CopilotException.class, exception -> {
                    assertThat(exception.code()).isEqualTo(ErrorCode.MODEL_RATE_LIMITED);
                    assertThat(exception.getMessage()).doesNotContain("upstream-secret-provider-response");
                });
        server.verify();
    }

    @Test
    void rejectsMalformedJsonAndMissingChoicesAsInvalidModelOutput() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        CompatibleChatModelGateway gateway = new CompatibleChatModelGateway(model(), builder);
        server.expect(requestTo("https://model.example.internal/v1/chat/completions"))
                .andRespond(withSuccess("{\"choices\":[]}", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> gateway.complete(request()))
                .isInstanceOf(CopilotException.class)
                .extracting(error -> ((CopilotException) error).code())
                .isEqualTo(ErrorCode.MODEL_OUTPUT_INVALID);
        server.verify();
    }

    @Test
    void rejectsInvalidJsonAsInvalidModelOutput() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        CompatibleChatModelGateway gateway = new CompatibleChatModelGateway(model(), builder);
        server.expect(requestTo("https://model.example.internal/v1/chat/completions"))
                .andRespond(withSuccess("not-json", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> gateway.complete(request()))
                .isInstanceOf(CopilotException.class)
                .extracting(error -> ((CopilotException) error).code())
                .isEqualTo(ErrorCode.MODEL_OUTPUT_INVALID);
        server.verify();
    }

    @Test
    void rejectsNegativeUsageTokensAsInvalidModelOutput() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        CompatibleChatModelGateway gateway = new CompatibleChatModelGateway(model(), builder);
        server.expect(requestTo("https://model.example.internal/v1/chat/completions"))
                .andRespond(withSuccess("""
                        {"choices":[{"message":{"content":"{}"}}],"usage":{"prompt_tokens":-1,"completion_tokens":2}}
                        """, MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> gateway.complete(request()))
                .isInstanceOf(CopilotException.class)
                .extracting(error -> ((CopilotException) error).code())
                .isEqualTo(ErrorCode.MODEL_OUTPUT_INVALID);
        server.verify();
    }

    private static ModelRequest request() {
        return new ModelRequest("request-1", "system policy", "immutable facts", "{\"type\":\"object\"}", 2000, 0d,
                "review-analysis-v1", "model-config-v1");
    }

    private static CopilotProperties.Model model() {
        return model("qwen", "qwen-plus");
    }

    private static CopilotProperties.Model model(String provider, String modelId) {
        return model(provider, modelId, URI.create("https://model.example.internal"));
    }

    private static CopilotProperties.Model model(String provider, String modelId, URI baseUrl) {
        return new CopilotProperties.Model(
                provider, modelId, baseUrl,
                new SecretValue("secret-for-test-only"), 32000, 2000, Duration.ofSeconds(20),
                "phase3-2026-08");
    }
}
