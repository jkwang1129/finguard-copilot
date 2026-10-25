package com.finguard.copilot.config;

import java.net.URI;
import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.autoconfigure.validation.ValidationAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.validation.BindValidationException;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

class CopilotPropertiesTest {

    private static final String[] VALID_PROPERTIES = {
            "finguard.copilot.core.base-url=https://core.example.internal",
            "finguard.copilot.embedding.dimensions=16",
            "finguard.copilot.retrieval.top-k=7"
    };

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    ConfigurationPropertiesAutoConfiguration.class,
                    ValidationAutoConfiguration.class
            ))
            .withUserConfiguration(PropertiesConfiguration.class);

    @Test
    void bindsValidatedProperties() {
        contextRunner.withPropertyValues(VALID_PROPERTIES).run(context -> {
            assertThat(context).hasNotFailed();
            CopilotProperties properties = context.getBean(CopilotProperties.class);
            assertThat(properties.core().baseUrl())
                    .isEqualTo(URI.create("https://core.example.internal"));
            assertThat(properties.core().connectTimeout()).isEqualTo(Duration.ofSeconds(2));
            assertThat(properties.core().readTimeout()).isEqualTo(Duration.ofSeconds(3));
            assertThat(properties.embedding().dimensions()).isEqualTo(16);
            assertThat(properties.retrieval().topK()).isEqualTo(7);
            assertThat(properties.jobs().enabled()).isFalse();
            assertThat(properties.jobs().batchSize()).isEqualTo(100);
            assertThat(properties.jobs().snapshotRetention()).isEqualTo(Duration.ofDays(30));
            assertThat(properties.jobs().publishedOutboxRetention()).isEqualTo(Duration.ofDays(7));
            assertThat(properties.jobs().fixedDelay()).isEqualTo(Duration.ofHours(1));
        });
    }

    @Test
    void bindsEnabledJobsAndRetentionSettings() {
        contextRunner.withPropertyValues(
                "finguard.copilot.core.base-url=https://core.example.internal",
                "finguard.copilot.embedding.dimensions=8",
                "finguard.copilot.retrieval.top-k=5",
                "finguard.copilot.jobs.enabled=true",
                "finguard.copilot.jobs.batch-size=100",
                "finguard.copilot.jobs.snapshot-retention=30d",
                "finguard.copilot.jobs.published-outbox-retention=7d",
                "finguard.copilot.jobs.fixed-delay=1h"
        ).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(CopilotProperties.class).jobs().enabled()).isTrue();
        });
    }

    @Test
    void bindsCustomCoreTimeouts() {
        contextRunner.withPropertyValues(
                "finguard.copilot.core.base-url=https://core.example.internal",
                "finguard.copilot.core.connect-timeout=750ms",
                "finguard.copilot.core.read-timeout=5s",
                "finguard.copilot.embedding.dimensions=8",
                "finguard.copilot.retrieval.top-k=5"
        ).run(context -> {
            assertThat(context).hasNotFailed();
            CopilotProperties properties = context.getBean(CopilotProperties.class);
            assertThat(properties.core().connectTimeout()).isEqualTo(Duration.ofMillis(750));
            assertThat(properties.core().readTimeout()).isEqualTo(Duration.ofSeconds(5));
        });
    }

    @ParameterizedTest
    @CsvSource({
            "connect-timeout,0s",
            "connect-timeout,-1s",
            "read-timeout,0s",
            "read-timeout,-1s"
    })
    void rejectsNonPositiveCoreTimeouts(String propertyName, String value) {
        contextRunner.withPropertyValues(
                "finguard.copilot.core.base-url=https://core.example.internal",
                "finguard.copilot.core." + propertyName + "=" + value,
                "finguard.copilot.embedding.dimensions=8",
                "finguard.copilot.retrieval.top-k=5"
        ).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure())
                    .hasRootCauseInstanceOf(BindValidationException.class);
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "ftp://core.example.internal",
            "https://user:pass@core.example.internal",
            "https://core.example.internal/path?debug=true",
            "https://core.example.internal/path#fragment"
    })
    void rejectsUnsafeCoreBaseUrl(String baseUrl) {
        contextRunner.withPropertyValues(
                "finguard.copilot.core.base-url=" + baseUrl,
                "finguard.copilot.embedding.dimensions=8",
                "finguard.copilot.retrieval.top-k=5"
        ).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure())
                    .hasRootCauseInstanceOf(BindValidationException.class);
        });
    }

    @ParameterizedTest
    @ValueSource(ints = {3, 4097})
    void rejectsOutOfRangeEmbeddingDimensions(int dimensions) {
        contextRunner.withPropertyValues(
                "finguard.copilot.core.base-url=https://core.example.internal",
                "finguard.copilot.embedding.dimensions=" + dimensions,
                "finguard.copilot.retrieval.top-k=5"
        ).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure())
                    .hasRootCauseInstanceOf(BindValidationException.class);
        });
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 101})
    void rejectsOutOfRangeRetrievalTopK(int topK) {
        contextRunner.withPropertyValues(
                "finguard.copilot.core.base-url=https://core.example.internal",
                "finguard.copilot.embedding.dimensions=8",
                "finguard.copilot.retrieval.top-k=" + topK
        ).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure())
                    .hasRootCauseInstanceOf(BindValidationException.class);
        });
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(CopilotProperties.class)
    static class PropertiesConfiguration {
    }
}
