package com.finguard.copilot.coreclient.http;

import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finguard.copilot.config.CopilotProperties;
import com.finguard.copilot.coreclient.port.CoreClient;
import com.finguard.copilot.coreclient.port.CoreClientException;
import com.finguard.copilot.coreclient.port.CurrentUser;
import com.finguard.copilot.coreclient.port.ReviewTaskContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class HttpCoreClientTest {

    private static final String BASE_URL = "https://core.example.internal";
    private static final String BEARER_TOKEN = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxIn0.signature";
    private static final String SENSITIVE_BODY = "upstream-body-must-not-escape";

    private RestClient.Builder restClientBuilder;
    private MockRestServiceServer server;
    private CoreClient client;

    @BeforeEach
    void setUp() {
        restClientBuilder = RestClient.builder();
        server = MockRestServiceServer.bindTo(restClientBuilder).build();
        client = new HttpCoreClient(
                restClientBuilder.baseUrl(BASE_URL).build(),
                new CoreHttpConfiguration().strictResponseObjectMapper(new ObjectMapper().findAndRegisterModules())
        );
    }

    @AfterEach
    void verifyServer() {
        server.verify();
    }

    @Test
    void mapsCurrentUserFromItsFixedGetPathAndKeepsRolesImmutable() {
        server.expect(once(), requestTo(BASE_URL + "/api/auth/me"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer " + BEARER_TOKEN))
                .andRespond(withSuccess("""
                        {"username":"reviewer","roles":["REVIEWER","ADMIN"]}
                        """, MediaType.APPLICATION_JSON));

        CurrentUser user = client.currentUser(BEARER_TOKEN);

        assertThat(user.username()).isEqualTo("reviewer");
        assertThat(user.roles()).containsExactlyInAnyOrder("REVIEWER", "ADMIN");
        assertThatThrownBy(() -> user.roles().add("ADMIN"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void mapsContextFromItsFixedGetPathIncludingZeroVersionAndNullRuleCode() {
        server.expect(once(), requestTo(BASE_URL + "/api/review-tasks/42/context"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer " + BEARER_TOKEN))
                .andRespond(withSuccess(validContextJson(), MediaType.APPLICATION_JSON));

        ReviewTaskContext context = client.reviewTaskContext(42L, BEARER_TOKEN);

        assertThat(context.reviewTask().id()).isEqualTo(42L);
        assertThat(context.reviewTask().version()).isZero();
        assertThat(context.reviewTask().ruleCode()).isNull();
        assertThat(context.transaction().amount()).isEqualByComparingTo("123.45");
        assertThat(context.riskFacts()).isEmpty();
        assertThat(context.capturedAt()).isEqualTo(Instant.parse("2026-08-23T00:00:00Z"));
        assertThatThrownBy(() -> context.riskFacts().add(new ReviewTaskContext.RiskFact("x", "y", "z")))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void rejectsUnknownCurrentUserFieldsAsInvalidResponse() {
        server.expect(requestTo(BASE_URL + "/api/auth/me"))
                .andRespond(withSuccess("""
                        {"username":"reviewer","roles":["REVIEWER"],"unexpected":"value"}
                        """, MediaType.APPLICATION_JSON));

        assertInvalidResponse(() -> client.currentUser(BEARER_TOKEN));
    }

    @Test
    void rejectsMissingContextFieldsAsInvalidResponse() {
        server.expect(requestTo(BASE_URL + "/api/review-tasks/42/context"))
                .andRespond(withSuccess("""
                        {
                          "reviewTask":{"id":42,"version":0,"status":"PENDING","reasonCode":"UNMATCHED","createdAt":"2026-08-22T10:15:30Z"},
                          "transaction":{"id":99,"accountId":7,"amount":123.45,"currency":"USD","occurredAt":"2026-08-22T09:00:00Z","sourceType":"CSV_IMPORT"},
                          "reconciliation":{"resultType":"UNMATCHED","matchMethod":"NONE","reasonCode":"UNMATCHED"},
                          "riskFacts":[],
                          "accountSummary":{"status":"ACTIVE","displayName":"Operating account"},
                          "capturedAt":"2026-08-23T00:00:00Z"
                        }
                        """, MediaType.APPLICATION_JSON));

        assertInvalidResponse(() -> client.reviewTaskContext(42L, BEARER_TOKEN));
    }

    @Test
    void rejectsMalformedContextFieldsAsInvalidResponse() {
        server.expect(requestTo(BASE_URL + "/api/review-tasks/42/context"))
                .andRespond(withSuccess(validContextJson().replace("\"id\":42", "\"id\":\"not-a-number\""),
                        MediaType.APPLICATION_JSON));

        assertInvalidResponse(() -> client.reviewTaskContext(42L, BEARER_TOKEN));
    }

    @ParameterizedTest
    @MethodSource("lossyContextBodies")
    void rejectsLossyContextScalarCoercionsAsInvalidResponse(String responseBody) {
        server.expect(requestTo(BASE_URL + "/api/review-tasks/42/context"))
                .andRespond(withSuccess(responseBody, MediaType.APPLICATION_JSON));

        assertInvalidResponse(() -> client.reviewTaskContext(42L, BEARER_TOKEN));
    }

    @Test
    void rejectsTrailingContextTokensAsInvalidResponse() {
        server.expect(requestTo(BASE_URL + "/api/review-tasks/42/context"))
                .andRespond(withSuccess(validContextJson() + " {}", MediaType.APPLICATION_JSON));

        assertInvalidResponse(() -> client.reviewTaskContext(42L, BEARER_TOKEN));
    }

    @ParameterizedTest
    @MethodSource("invalidRoleBodies")
    void rejectsNullOrUnknownRolesAsInvalidResponse(String responseBody) {
        server.expect(requestTo(BASE_URL + "/api/auth/me"))
                .andRespond(withSuccess(responseBody, MediaType.APPLICATION_JSON));

        assertInvalidResponse(() -> client.currentUser(BEARER_TOKEN));
    }

    @ParameterizedTest
    @MethodSource("invalidRiskFactLists")
    void rejectsNullRiskFactListsAndElementsAsInvalidResponse(String responseBody) {
        server.expect(requestTo(BASE_URL + "/api/review-tasks/42/context"))
                .andRespond(withSuccess(responseBody, MediaType.APPLICATION_JSON));

        assertInvalidResponse(() -> client.reviewTaskContext(42L, BEARER_TOKEN));
    }

    @ParameterizedTest
    @MethodSource("redirectBodies")
    void mapsEveryRedirectToUnavailableEvenWithAContractShapedBody(String responseBody) {
        server.expect(requestTo(BASE_URL + "/api/auth/me"))
                .andRespond(withStatus(HttpStatus.FOUND)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(responseBody));

        assertSafeFailureWithLogs(
                () -> client.currentUser(BEARER_TOKEN),
                CoreClientException.Kind.UNAVAILABLE,
                BEARER_TOKEN,
                responseBody
        );
    }

    @ParameterizedTest
    @MethodSource("upstreamStatusMappings")
    void mapsUpstreamStatusesWithoutExposingTokenOrResponseBody(
            HttpStatus status,
            CoreClientException.Kind expectedKind
    ) {
        server.expect(requestTo(BASE_URL + "/api/auth/me"))
                .andRespond(withStatus(status)
                        .contentType(MediaType.TEXT_PLAIN)
                        .body(SENSITIVE_BODY));

        assertSafeFailureWithLogs(
                () -> client.currentUser(BEARER_TOKEN),
                expectedKind,
                BEARER_TOKEN,
                SENSITIVE_BODY
        );
    }

    @Test
    void mapsIoFailuresToUnavailableWithoutExposingToken() {
        ClientHttpRequestFactory failingRequestFactory = (uri, httpMethod) -> {
            throw new IOException(SENSITIVE_BODY);
        };
        CoreClient failingClient = new HttpCoreClient(
                RestClient.builder().requestFactory(failingRequestFactory).build(),
                new CoreHttpConfiguration().strictResponseObjectMapper(new ObjectMapper().findAndRegisterModules())
        );

        assertSafeFailureWithLogs(
                () -> failingClient.currentUser(BEARER_TOKEN),
                CoreClientException.Kind.UNAVAILABLE,
                BEARER_TOKEN,
                SENSITIVE_BODY
        );
    }

    @Test
    void mapsGenericRestClientFailuresToUnavailableWithoutLeakingInfrastructureDetails() {
        CoreClient failingClient = new HttpCoreClient(
                RestClient.builder()
                        .requestInterceptor((request, body, execution) -> {
                            throw new RestClientException(SENSITIVE_BODY);
                        })
                        .build(),
                new CoreHttpConfiguration().strictResponseObjectMapper(new ObjectMapper().findAndRegisterModules())
        );

        assertSafeFailureWithLogs(
                () -> failingClient.currentUser(BEARER_TOKEN),
                CoreClientException.Kind.UNAVAILABLE,
                BEARER_TOKEN,
                SENSITIVE_BODY
        );
    }

    @Test
    void mapsRequestConstructionIllegalArgumentExceptionsToUnauthenticatedWithoutLeakingDetails() {
        CoreClient failingClient = new HttpCoreClient(
                RestClient.builder()
                        .requestInterceptor((request, body, execution) -> {
                            throw new IllegalArgumentException(SENSITIVE_BODY);
                        })
                        .build(),
                new CoreHttpConfiguration().strictResponseObjectMapper(new ObjectMapper().findAndRegisterModules())
        );

        assertSafeFailureWithLogs(
                () -> failingClient.currentUser(BEARER_TOKEN),
                CoreClientException.Kind.UNAUTHENTICATED,
                BEARER_TOKEN,
                SENSITIVE_BODY
        );
    }

    @Test
    void rejectsMalformedJsonWithoutLeakingItsBodyToExceptionsOrLogs() {
        String malformedJson = "{\"token\":\"" + SENSITIVE_BODY;
        server.expect(requestTo(BASE_URL + "/api/auth/me"))
                .andRespond(withSuccess(malformedJson, MediaType.APPLICATION_JSON));

        assertSafeFailureWithLogs(
                () -> client.currentUser(BEARER_TOKEN),
                CoreClientException.Kind.INVALID_RESPONSE,
                BEARER_TOKEN,
                SENSITIVE_BODY
        );
    }

    @Test
    void rejectsMalformedJdkBearerTokensBeforeHeaderConstructionWithoutLeakingThem() {
        String malformedBearerToken = "not-a-jwt\u0001" + SENSITIVE_BODY;
        CoreClient jdkClient = new CoreHttpConfiguration().coreClient(
                RestClient.builder(),
                new CopilotProperties(
                        new CopilotProperties.Core(URI.create(BASE_URL), Duration.ofSeconds(1), Duration.ofSeconds(1)),
                        new CopilotProperties.Embedding(8),
                        new CopilotProperties.Retrieval(5)
                ),
                new ObjectMapper().findAndRegisterModules()
        );

        assertSafeFailureWithLogs(
                () -> jdkClient.currentUser(malformedBearerToken),
                CoreClientException.Kind.UNAUTHENTICATED,
                malformedBearerToken,
                SENSITIVE_BODY
        );
    }

    private static List<Arguments> upstreamStatusMappings() {
        return List.of(
                Arguments.of(HttpStatus.UNAUTHORIZED, CoreClientException.Kind.UNAUTHENTICATED),
                Arguments.of(HttpStatus.FORBIDDEN, CoreClientException.Kind.FORBIDDEN),
                Arguments.of(HttpStatus.NOT_FOUND, CoreClientException.Kind.NOT_FOUND),
                Arguments.of(HttpStatus.TOO_MANY_REQUESTS, CoreClientException.Kind.UNAVAILABLE),
                Arguments.of(HttpStatus.INTERNAL_SERVER_ERROR, CoreClientException.Kind.UNAVAILABLE),
                Arguments.of(HttpStatus.BAD_GATEWAY, CoreClientException.Kind.UNAVAILABLE)
        );
    }

    private static List<Arguments> lossyContextBodies() {
        return List.of(
                Arguments.of(validContextJson().replace("\"id\":42", "\"id\":42.9")),
                Arguments.of(validContextJson().replace("\"status\":\"PENDING\"", "\"status\":123")),
                Arguments.of(validContextJson().replace("\"sourceType\":\"CSV_IMPORT\"", "\"sourceType\":true")),
                Arguments.of(validContextJson().replace("\"amount\":123.45", "\"amount\":\"123.45\""))
        );
    }

    private static List<Arguments> invalidRoleBodies() {
        return List.of(
                Arguments.of("{\"username\":\"reviewer\",\"roles\":null}"),
                Arguments.of("{\"username\":\"reviewer\",\"roles\":[\"REVIEWER\",null]}"),
                Arguments.of("{\"username\":\"reviewer\",\"roles\":[\"AUDITOR\"]}")
        );
    }

    private static List<Arguments> invalidRiskFactLists() {
        return List.of(
                Arguments.of(validContextJson().replace("\"riskFacts\":[]", "\"riskFacts\":null")),
                Arguments.of(validContextJson().replace("\"riskFacts\":[]", "\"riskFacts\":[null]"))
        );
    }

    private static List<Arguments> redirectBodies() {
        return List.of(Arguments.of(""), Arguments.of(validCurrentUserJson()));
    }

    private static void assertInvalidResponse(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        assertThatThrownBy(call)
                .isInstanceOf(CoreClientException.class)
                .satisfies(exception -> {
                    CoreClientException coreException = (CoreClientException) exception;
                    assertThat(coreException.kind()).isEqualTo(CoreClientException.Kind.INVALID_RESPONSE);
                    assertThat(coreException).hasNoCause();
                });
    }

    private static void assertSafeFailureWithLogs(
            org.assertj.core.api.ThrowableAssert.ThrowingCallable call,
            CoreClientException.Kind expectedKind,
            String... sensitiveValues
    ) {
        Logger logger = (Logger) LoggerFactory.getLogger(HttpCoreClient.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            assertThatThrownBy(call)
                    .isInstanceOf(CoreClientException.class)
                    .satisfies(exception -> {
                        CoreClientException coreException = (CoreClientException) exception;
                        assertThat(coreException.kind()).isEqualTo(expectedKind);
                        assertThat(coreException).hasNoCause();
                        assertThat(coreException.getMessage()).doesNotContain(nonEmpty(sensitiveValues));
                    });
            assertThat(appender.list)
                    .allSatisfy(event -> assertThat(event.getFormattedMessage()).doesNotContain(nonEmpty(sensitiveValues)));
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    private static String[] nonEmpty(String... values) {
        return java.util.Arrays.stream(values).filter(value -> !value.isEmpty()).toArray(String[]::new);
    }

    private static String validCurrentUserJson() {
        return "{\"username\":\"reviewer\",\"roles\":[\"REVIEWER\",\"ADMIN\"]}";
    }

    private static String validContextJson() {
        return """
                {
                  "reviewTask":{"id":42,"version":0,"status":"PENDING","ruleCode":null,"reasonCode":"UNMATCHED","createdAt":"2026-08-22T10:15:30Z"},
                  "transaction":{"id":99,"accountId":7,"amount":123.45,"currency":"USD","occurredAt":"2026-08-22T09:00:00Z","sourceType":"CSV_IMPORT"},
                  "reconciliation":{"resultType":"UNMATCHED","matchMethod":"NONE","reasonCode":"UNMATCHED"},
                  "riskFacts":[],
                  "accountSummary":{"status":"ACTIVE","displayName":"Operating account"},
                  "capturedAt":"2026-08-23T00:00:00Z"
                }
                """;
    }
}
