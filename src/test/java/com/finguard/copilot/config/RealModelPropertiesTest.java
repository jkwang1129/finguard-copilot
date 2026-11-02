package com.finguard.copilot.config;

import java.net.URI;
import java.io.PrintWriter;
import java.io.StringWriter;

import com.finguard.copilot.foundation.stub.StubFoundationConfiguration;
import com.finguard.copilot.analysis.application.AnalysisOrchestrator;
import com.finguard.copilot.model.config.RealModelConfiguration;
import com.finguard.copilot.model.port.ModelGateway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.autoconfigure.validation.ValidationAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RealModelPropertiesTest {

    private static final String[] BASE_PROPERTIES = {
            "finguard.copilot.core.base-url=https://core.example.internal",
            "finguard.copilot.embedding.dimensions=16",
            "finguard.copilot.retrieval.top-k=7"
    };

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    ConfigurationPropertiesAutoConfiguration.class,
                    org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration.class,
                    ValidationAutoConfiguration.class
            ))
            .withUserConfiguration(PropertiesConfiguration.class);

    @ParameterizedTest
    @CsvSource({
            "real-qwen, qwen, qwen-plus, https://dashscope.aliyuncs.com",
            "real-deepseek, deepseek, deepseek-chat, https://api.deepseek.com",
            "real-zhipu, zhipu, glm-test, https://open.bigmodel.cn/api/paas/v4"
    })
    void bindsValidRealModelConfiguration(String profile, String provider, String modelId, String baseUrl) {
        contextRunner
                .withUserConfiguration(RealModelConfiguration.class)
                .withPropertyValues(
                        BASE_PROPERTIES
                )
                .withPropertyValues(
                        "spring.profiles.active=" + profile,
                        "finguard.copilot.model.provider=" + provider,
                        "finguard.copilot.model.model-id=" + modelId,
                        "finguard.copilot.model.base-url=" + baseUrl,
                        "finguard.copilot.model.api-key=secret-for-test-only",
                        "finguard.copilot.model.price-table-version=free-v1",
                        "finguard.copilot.prompt.version=review-analysis-v1",
                        "finguard.copilot.budget.max-input-tokens=32000",
                        "finguard.copilot.budget.max-output-tokens=2000",
                        "finguard.copilot.budget.max-model-calls=1"
                )
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(ModelGateway.class);
                    assertThat(context).hasSingleBean(AnalysisOrchestrator.class);
                    CopilotProperties properties = context.getBean(CopilotProperties.class);
                    assertThat(properties.model().provider()).isEqualTo(provider);
                    assertThat(properties.model().modelId()).isEqualTo(modelId);
                    assertThat(properties.model().baseUrl()).isEqualTo(URI.create(baseUrl));
                    if ("zhipu".equals(provider)) {
                        assertThat(properties.model().chatCompletionsPath()).isEqualTo("/chat/completions");
                    }
                    assertThat(properties.model().apiKey().value()).isEqualTo("secret-for-test-only");
                    assertThat(properties.model().toString()).contains("[REDACTED]")
                            .doesNotContain("secret-for-test-only");
                    assertThat(properties.prompt().version()).isEqualTo("review-analysis-v1");
                    assertThat(properties.budget().maxInputTokens()).isEqualTo(32000);
                    assertThat(properties.budget().maxOutputTokens()).isEqualTo(2000);
                    assertThat(properties.budget().maxModelCalls()).isEqualTo(1);
                });
    }

    @ParameterizedTest
    @CsvSource({
            "real-qwen, qwen",
            "real-deepseek, deepseek",
            "real-zhipu, zhipu"
    })
    void rejectsRealProfileWithoutApiKey(String profile, String provider) {
        contextRunner
                .withUserConfiguration(RealModelConfiguration.class)
                .withPropertyValues(
                        BASE_PROPERTIES
                )
                .withPropertyValues(
                        "spring.profiles.active=" + profile,
                        "finguard.copilot.model.provider=" + provider,
                        "finguard.copilot.model.model-id=model-under-test",
                        "finguard.copilot.model.base-url=https://model.example.internal"
                )
                .run(context -> {
                    assertThat(context).hasFailed();
                    String failure = stackTrace(context.getStartupFailure());
                    assertThat(failure)
                            .contains("finguard.copilot.model.api-key")
                            .doesNotContain("secret");
                });
    }

    @Test
    void createsRequiredQdrantRetrievalWithBootAutoConfiguredObjectMapper() {
        contextRunner.withConfiguration(AutoConfigurations.of(
                        org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration.class))
                .withBean(com.finguard.copilot.knowledge.persistence.KnowledgeIndexRepository.class,
                        () -> org.mockito.Mockito.mock(com.finguard.copilot.knowledge.persistence.KnowledgeIndexRepository.class))
                .withBean(com.finguard.copilot.knowledge.persistence.KnowledgeDocumentRepository.class,
                        () -> org.mockito.Mockito.mock(com.finguard.copilot.knowledge.persistence.KnowledgeDocumentRepository.class))
                .withUserConfiguration(RealModelConfiguration.class)
                .withPropertyValues(BASE_PROPERTIES)
                .withPropertyValues("spring.profiles.active=real-qwen",
                        "finguard.copilot.model.provider=qwen", "finguard.copilot.model.model-id=qwen-plus",
                        "finguard.copilot.model.base-url=https://dashscope.aliyuncs.com/compatible-mode/v1",
                        "finguard.copilot.model.api-key=secret-for-test-only",
                        "finguard.copilot.model.price-table-version=free-v1")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(com.finguard.copilot.knowledge.port.VectorStore.class);
                    assertThat(context).hasSingleBean(com.finguard.copilot.knowledge.retrieval.KnowledgeRetrievalService.class);
                });
    }

    @Test
    void bindsReviewedNumericRuleIntoTheRealModelConfiguration() throws Exception {
        String text = "approved numeric policy";
        String checksum = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                .digest(text.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        contextRunner.withUserConfiguration(RealModelConfiguration.class)
                .withPropertyValues(BASE_PROPERTIES)
                .withPropertyValues("spring.profiles.active=real-qwen",
                        "finguard.copilot.model.provider=qwen", "finguard.copilot.model.model-id=qwen-plus",
                        "finguard.copilot.model.base-url=https://dashscope.aliyuncs.com/compatible-mode/v1",
                        "finguard.copilot.model.api-key=secret-for-test-only",
                        "finguard.copilot.model.price-table-version=free-v1",
                        "finguard.copilot.numeric-guard.rules[0].version=threshold-v1",
                        "finguard.copilot.numeric-guard.rules[0].rule-code=HO_RULE_03",
                        "finguard.copilot.numeric-guard.rules[0].currency=USD",
                        "finguard.copilot.numeric-guard.rules[0].operator=GT",
                        "finguard.copilot.numeric-guard.rules[0].threshold=0.100000000000000001",
                        "finguard.copilot.numeric-guard.rules[0].document-id=HO-P03",
                        "finguard.copilot.numeric-guard.rules[0].document-version=1.0",
                        "finguard.copilot.numeric-guard.rules[0].section-path=threshold",
                        "finguard.copilot.numeric-guard.rules[0].content-sha256=" + checksum)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(com.finguard.copilot.guardrail.NumericThresholdGuard.class);
                    assertThat(context.getBean(NumericGuardProperties.class).rules()).singleElement()
                            .satisfies(rule -> assertThat(rule.threshold())
                                    .isEqualByComparingTo("0.100000000000000001"));
                    java.time.Instant now = java.time.Instant.parse("2026-10-27T00:00:00Z");
                    var amount = new java.math.BigDecimal("0.100000000000000002");
                    var facts = new com.finguard.copilot.coreclient.port.ReviewTaskContext(
                            new com.finguard.copilot.coreclient.port.ReviewTaskContext.ReviewTask(
                                    1, 0, "PENDING", "HO_RULE_03", "ALERT", now),
                            new com.finguard.copilot.coreclient.port.ReviewTaskContext.Transaction(
                                    2, 3, amount, "USD", now, "SYNTHETIC"),
                            new com.finguard.copilot.coreclient.port.ReviewTaskContext.Reconciliation(
                                    "MATCHED", "REFERENCE", "ALERT"), java.util.List.of(),
                            new com.finguard.copilot.coreclient.port.ReviewTaskContext.AccountSummary("ACTIVE", "masked"), now);
                    var result = new com.finguard.copilot.analysis.domain.AnalysisResult(1,
                            java.util.UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"), "summary", "explanation",
                            java.util.List.of(new com.finguard.copilot.analysis.domain.AnalysisResult.BusinessEvidence(
                                    2, 3, amount, "USD", "HO_RULE_03", "ALERT", java.util.List.of("transaction.amount"))),
                            java.util.List.of(new com.finguard.copilot.analysis.domain.AnalysisResult.PolicyCitation(
                                    "HO-P03", "1.0", "threshold", text)),
                            com.finguard.copilot.analysis.domain.AnalysisRecommendation.IGNORED,
                            "wrong", java.util.List.of(), java.util.List.of());
                    var citation = new com.finguard.copilot.knowledge.domain.KnowledgeCitation(
                            "HO-P03", "1.0", "chunk", "threshold", checksum, 0.9, "published-v1");
                    assertThatThrownBy(() -> context.getBean(com.finguard.copilot.guardrail.NumericThresholdGuard.class)
                            .validate(facts, result, java.util.List.of(
                                    new com.finguard.copilot.knowledge.retrieval.KnowledgeEvidence(citation, text))))
                            .isInstanceOf(com.finguard.copilot.common.error.CopilotException.class);
                });
    }

    @Test
    void bindsReviewedPolicyConditionsAndAuthorityIntoProductionOrchestrator() {
        contextRunner.withUserConfiguration(RealModelConfiguration.class).withPropertyValues(BASE_PROPERTIES)
                .withPropertyValues("spring.profiles.active=real-qwen","finguard.copilot.model.provider=qwen",
                        "finguard.copilot.model.model-id=qwen-plus","finguard.copilot.model.base-url=https://dashscope.aliyuncs.com/compatible-mode/v1",
                        "finguard.copilot.model.api-key=test-only","finguard.copilot.model.price-table-version=test",
                        "finguard.copilot.reviewed-policy.sets[0].version=all-v1",
                        "finguard.copilot.reviewed-policy.sets[0].rule-code=COMBINED",
                        "finguard.copilot.reviewed-policy.sets[0].currency=USD",
                        "finguard.copilot.reviewed-policy.sets[0].policies[0].binding.document-id=approved",
                        "finguard.copilot.reviewed-policy.sets[0].policies[0].binding.document-version=v1",
                        "finguard.copilot.reviewed-policy.sets[0].policies[0].binding.section-path=Document",
                        "finguard.copilot.reviewed-policy.sets[0].policies[0].binding.content-sha256="+"a".repeat(64),
                        "finguard.copilot.reviewed-policy.sets[0].policies[0].conditions[0].field=AMOUNT",
                        "finguard.copilot.reviewed-policy.sets[0].policies[0].conditions[0].operator=GTE",
                        "finguard.copilot.reviewed-policy.sets[0].policies[0].conditions[0].value=100.000000000000000001",
                        "finguard.copilot.reviewed-policy.sets[0].policies[0].on-match=CONFIRMED",
                        "finguard.copilot.reviewed-policy.sets[0].policies[0].on-miss=IGNORED")
                .run(context->{
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(com.finguard.copilot.guardrail.ReviewedPolicyGuard.class).ruleCodes()).containsExactly("COMBINED");
                    assertThat(context.getBean(ReviewedPolicyProperties.class).sets().get(0).policies().get(0).conditions().get(0).value())
                            .isEqualTo("100.000000000000000001");
                });
    }

    @Test
    void defaultProfileDoesNotCreateModelGateway() {
        contextRunner
                .withUserConfiguration(RealModelConfiguration.class, StubFoundationConfiguration.class)
                .withPropertyValues(BASE_PROPERTIES)
                .run(context -> assertThat(context).doesNotHaveBean(ModelGateway.class));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "ftp://model.example.internal",
            "https://user:pass@model.example.internal"
    })
    void rejectsUnsafeModelBaseUrl(String baseUrl) {
        contextRunner.withPropertyValues(
                        BASE_PROPERTIES
                )
                .withPropertyValues(
                        "finguard.copilot.model.provider=qwen",
                        "finguard.copilot.model.model-id=qwen-plus",
                        "finguard.copilot.model.base-url=" + baseUrl,
                        "finguard.copilot.model.api-key=secret-for-test-only"
                )
                .run(context -> {
                    assertThat(context).hasFailed();
                    String failure = stackTrace(context.getStartupFailure());
                    assertThat(failure)
                            .contains("model.base-url")
                            .doesNotContain("secret-for-test-only");
                });
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "https://model.example.internal/chat/completions",
            "/chat/completions?debug=true",
            "chat/completions"
    })
    void rejectsUnsafeChatCompletionsPath(String path) {
        contextRunner.withPropertyValues(
                        BASE_PROPERTIES
                )
                .withPropertyValues(
                        "spring.profiles.active=real-zhipu",
                        "finguard.copilot.model.provider=zhipu",
                        "finguard.copilot.model.model-id=glm-test",
                        "finguard.copilot.model.base-url=https://open.bigmodel.cn/api/paas/v4/",
                        "finguard.copilot.model.api-key=secret-for-test-only",
                        "finguard.copilot.model.chat-completions-path=" + path
                )
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(stackTrace(context.getStartupFailure()))
                            .contains("chat-completions-path")
                            .doesNotContain("secret-for-test-only");
                });
    }

    @ParameterizedTest
    @CsvSource({
            "max-input-tokens,32001",
            "max-output-tokens,2001",
            "max-model-calls,2"
    })
    void rejectsUnsafeBudget(String field, int value) {
        contextRunner.withPropertyValues(
                        BASE_PROPERTIES
                )
                .withPropertyValues("finguard.copilot.budget." + field + "=" + value)
                .run(context -> assertThat(context).hasFailed());
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(CopilotProperties.class)
    static class PropertiesConfiguration {
    }

    private static String stackTrace(Throwable failure) {
        StringWriter output = new StringWriter();
        failure.printStackTrace(new PrintWriter(output));
        return output.toString();
    }
}
