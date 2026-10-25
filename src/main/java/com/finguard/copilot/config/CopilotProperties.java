package com.finguard.copilot.config;

import java.net.URI;
import java.math.BigDecimal;
import java.time.Duration;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("finguard.copilot")
public record CopilotProperties(
        @Valid @NotNull Core core,
        @Valid @NotNull Embedding embedding,
        @Valid @NotNull Retrieval retrieval,
        @Valid @NotNull Dispatch dispatch,
        @Valid @NotNull Jobs jobs,
        @Valid @NotNull Model model,
        @Valid @NotNull Prompt prompt,
        @Valid @NotNull Budget budget
) {

    @ConstructorBinding
    public CopilotProperties(Core core, Embedding embedding, Retrieval retrieval, Dispatch dispatch,
                             Jobs jobs, Model model, Prompt prompt, Budget budget) {
        this.core = core;
        this.embedding = embedding;
        this.retrieval = retrieval;
        this.dispatch = dispatch == null ? Dispatch.defaults() : dispatch;
        this.jobs = jobs == null ? Jobs.defaults() : jobs;
        this.model = model == null ? Model.defaults() : model;
        this.prompt = prompt == null ? Prompt.defaults() : prompt;
        this.budget = budget == null ? Budget.defaults() : budget;
    }

    public CopilotProperties(Core core, Embedding embedding, Retrieval retrieval, Dispatch dispatch,
                             Jobs jobs) {
        this(core, embedding, retrieval, dispatch, jobs, Model.defaults(), Prompt.defaults(), Budget.defaults());
    }

    public CopilotProperties(Core core, Embedding embedding, Retrieval retrieval, Dispatch dispatch) {
        this(core, embedding, retrieval, dispatch, Jobs.defaults());
    }

    public CopilotProperties(Core core, Embedding embedding, Retrieval retrieval) {
        this(core, embedding, retrieval, Dispatch.defaults(), Jobs.defaults());
    }

    public record Model(
            String provider,
            String modelId,
            URI baseUrl,
            SecretValue apiKey,
            String chatCompletionsPath,
            @Min(1) @Max(32000) @DefaultValue("32000") int inputTokenLimit,
            @Min(1) @Max(2000) @DefaultValue("2000") int outputTokenLimit,
            @DefaultValue("20s") Duration timeout,
            String priceTableVersion,
            String currency,
            BigDecimal inputPricePerMillion,
            BigDecimal outputPricePerMillion
    ) {

        public static final int DEFAULT_INPUT_TOKEN_LIMIT = 32000;
        public static final int DEFAULT_OUTPUT_TOKEN_LIMIT = 2000;
        public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(20);
        public static final String DEFAULT_PRICE_TABLE_VERSION = "unset";
        public static final String DEFAULT_OPENAI_CHAT_COMPLETIONS_PATH = "/v1/chat/completions";
        public static final String DEFAULT_ZHIPU_CHAT_COMPLETIONS_PATH = "/chat/completions";

        @ConstructorBinding
        public Model {
            provider = provider == null ? "" : provider.trim();
            modelId = modelId == null ? "" : modelId.trim();
            apiKey = apiKey == null ? SecretValue.empty() : apiKey;
            chatCompletionsPath = chatCompletionsPath == null || chatCompletionsPath.isBlank()
                    ? defaultChatCompletionsPath(provider) : chatCompletionsPath.trim();
            timeout = timeout == null ? DEFAULT_TIMEOUT : timeout;
            priceTableVersion = priceTableVersion == null || priceTableVersion.isBlank()
                    ? DEFAULT_PRICE_TABLE_VERSION : priceTableVersion.trim();
            currency = currency == null || currency.isBlank() ? "USD" : currency.trim();
            inputPricePerMillion = inputPricePerMillion == null ? BigDecimal.ZERO : inputPricePerMillion;
            outputPricePerMillion = outputPricePerMillion == null ? BigDecimal.ZERO : outputPricePerMillion;
            if (inputPricePerMillion.signum() < 0 || outputPricePerMillion.signum() < 0) {
                throw new IllegalArgumentException("model prices must not be negative");
            }
        }

        public Model(
                String provider,
                String modelId,
                URI baseUrl,
                SecretValue apiKey,
                int inputTokenLimit,
                int outputTokenLimit,
                Duration timeout,
                String priceTableVersion
        ) {
            this(provider, modelId, baseUrl, apiKey, null, inputTokenLimit, outputTokenLimit, timeout,
                    priceTableVersion, "USD", BigDecimal.ZERO, BigDecimal.ZERO);
        }

        public Model(
                String provider,
                String modelId,
                URI baseUrl,
                SecretValue apiKey,
                String chatCompletionsPath,
                int inputTokenLimit,
                int outputTokenLimit,
                Duration timeout,
                String priceTableVersion
        ) {
            this(provider, modelId, baseUrl, apiKey, chatCompletionsPath, inputTokenLimit, outputTokenLimit,
                    timeout, priceTableVersion, "USD", BigDecimal.ZERO, BigDecimal.ZERO);
        }

        public static Model defaults() {
            return new Model("", "", null, SecretValue.empty(), DEFAULT_INPUT_TOKEN_LIMIT,
                    DEFAULT_OUTPUT_TOKEN_LIMIT, DEFAULT_TIMEOUT, DEFAULT_PRICE_TABLE_VERSION);
        }

        @AssertTrue(message = "model.chat-completions-path must be a relative HTTP path")
        public boolean isSafeChatCompletionsPath() {
            return chatCompletionsPath != null
                    && chatCompletionsPath.startsWith("/")
                    && !chatCompletionsPath.contains("?")
                    && !chatCompletionsPath.contains("#")
                    && !chatCompletionsPath.contains("://")
                    && !chatCompletionsPath.contains("\\");
        }

        @AssertTrue(message = "model.base-url must be an absolute HTTP(S) URI without credentials, query, or fragment")
        public boolean isSafeBaseUrl() {
            if (baseUrl == null) {
                return true;
            }
            boolean supportedScheme = "http".equalsIgnoreCase(baseUrl.getScheme())
                    || "https".equalsIgnoreCase(baseUrl.getScheme());
            return supportedScheme
                    && baseUrl.getHost() != null
                    && baseUrl.getUserInfo() == null
                    && baseUrl.getQuery() == null
                    && baseUrl.getFragment() == null;
        }

        @AssertTrue(message = "model.timeout must be positive")
        public boolean hasPositiveTimeout() {
            return timeout != null && !timeout.isZero() && !timeout.isNegative();
        }

        public void validateForRealProfile(String profile) {
            requireText(provider, "finguard.copilot.model.provider");
            requireText(modelId, "finguard.copilot.model.model-id");
            if (baseUrl == null || !isSafeBaseUrl()) {
                throw new IllegalStateException("finguard.copilot.model.base-url must be a safe HTTP(S) URL");
            }
            if (!apiKey.isPresent()) {
                throw new IllegalStateException("finguard.copilot.model.api-key must be supplied for real model profile");
            }
            if (DEFAULT_PRICE_TABLE_VERSION.equals(priceTableVersion)) {
                throw new IllegalStateException("finguard.copilot.model.price-table-version must be supplied for real model profile");
            }
            if (!isSafeChatCompletionsPath()) {
                throw new IllegalStateException("finguard.copilot.model.chat-completions-path must be a relative HTTP path");
            }
            if ("real-qwen".equals(profile) && !"qwen".equalsIgnoreCase(provider)) {
                throw new IllegalStateException("finguard.copilot.model.provider must be qwen for real-qwen profile");
            }
            if ("real-deepseek".equals(profile) && !"deepseek".equalsIgnoreCase(provider)) {
                throw new IllegalStateException("finguard.copilot.model.provider must be deepseek for real-deepseek profile");
            }
            if ("real-zhipu".equals(profile) && !"zhipu".equalsIgnoreCase(provider)) {
                throw new IllegalStateException("finguard.copilot.model.provider must be zhipu for real-zhipu profile");
            }
            if (!"qwen".equalsIgnoreCase(provider)
                    && !"deepseek".equalsIgnoreCase(provider)
                    && !"zhipu".equalsIgnoreCase(provider)) {
                throw new IllegalStateException("finguard.copilot.model.provider must be qwen, deepseek, or zhipu");
            }
        }

        private static String defaultChatCompletionsPath(String provider) {
            return "zhipu".equalsIgnoreCase(provider)
                    ? DEFAULT_ZHIPU_CHAT_COMPLETIONS_PATH : DEFAULT_OPENAI_CHAT_COMPLETIONS_PATH;
        }

        private static void requireText(String value, String field) {
            if (value == null || value.isBlank()) {
                throw new IllegalStateException(field + " must not be blank for real model profile");
            }
        }
    }

    public record Prompt(String version) {

        public Prompt {
            version = version == null || version.isBlank() ? "review-analysis-v1" : version.trim();
        }

        public static Prompt defaults() {
            return new Prompt("review-analysis-v1");
        }
    }

    public record Budget(
            @Min(1) @Max(32000) @DefaultValue("32000") int maxInputTokens,
            @Min(1) @Max(2000) @DefaultValue("2000") int maxOutputTokens,
            @Min(1) @Max(1) @DefaultValue("1") int maxModelCalls
    ) {

        public static Budget defaults() {
            return new Budget(32000, 2000, 1);
        }
    }

    public record Core(
            @NotNull URI baseUrl,
            @DefaultValue("2s") Duration connectTimeout,
            @DefaultValue("3s") Duration readTimeout
    ) {

        public static final Duration DEFAULT_CONNECT_TIMEOUT = Duration.ofSeconds(2);
        public static final Duration DEFAULT_READ_TIMEOUT = Duration.ofSeconds(3);

        public Core {
            connectTimeout = connectTimeout == null ? DEFAULT_CONNECT_TIMEOUT : connectTimeout;
            readTimeout = readTimeout == null ? DEFAULT_READ_TIMEOUT : readTimeout;
        }

        @AssertTrue(message = "core.base-url must be an absolute HTTP(S) URI without credentials, query, or fragment")
        public boolean isSafeBaseUrl() {
            if (baseUrl == null || !baseUrl.isAbsolute()) {
                return false;
            }

            boolean supportedScheme = "http".equalsIgnoreCase(baseUrl.getScheme())
                    || "https".equalsIgnoreCase(baseUrl.getScheme());
            return supportedScheme
                    && baseUrl.getHost() != null
                    && baseUrl.getUserInfo() == null
                    && baseUrl.getQuery() == null
                    && baseUrl.getFragment() == null;
        }

        @AssertTrue(message = "core.connect-timeout must be positive")
        public boolean isPositiveConnectTimeout() {
            return connectTimeout != null && !connectTimeout.isZero() && !connectTimeout.isNegative();
        }

        @AssertTrue(message = "core.read-timeout must be positive")
        public boolean isPositiveReadTimeout() {
            return readTimeout != null && !readTimeout.isZero() && !readTimeout.isNegative();
        }
    }

    public record Embedding(@Min(4) @Max(4096) int dimensions) {
    }

    public record Retrieval(@Min(1) @Max(100) int topK) {
    }

    public record Dispatch(
            @DefaultValue("true") boolean relayEnabled,
            @Min(1) @Max(1000) @DefaultValue("100") int outboxBatchSize,
            @DefaultValue("1s") Duration outboxRetryInitialDelay,
            @DefaultValue("30s") Duration outboxRetryMaxDelay,
            @DefaultValue("1s") Duration relayPollInterval,
            @DefaultValue("5s") Duration publisherConfirmTimeout,
            @Min(1) @Max(3) @DefaultValue("3") int workerMaxAttempts
    ) {

        public static final Duration DEFAULT_OUTBOX_RETRY_INITIAL_DELAY = Duration.ofSeconds(1);
        public static final Duration DEFAULT_OUTBOX_RETRY_MAX_DELAY = Duration.ofSeconds(30);
        public static final Duration DEFAULT_RELAY_POLL_INTERVAL = Duration.ofSeconds(1);
        public static final Duration DEFAULT_PUBLISHER_CONFIRM_TIMEOUT = Duration.ofSeconds(5);

        public Dispatch {
            outboxRetryInitialDelay = outboxRetryInitialDelay == null
                    ? DEFAULT_OUTBOX_RETRY_INITIAL_DELAY : outboxRetryInitialDelay;
            outboxRetryMaxDelay = outboxRetryMaxDelay == null
                    ? DEFAULT_OUTBOX_RETRY_MAX_DELAY : outboxRetryMaxDelay;
            relayPollInterval = relayPollInterval == null
                    ? DEFAULT_RELAY_POLL_INTERVAL : relayPollInterval;
            publisherConfirmTimeout = publisherConfirmTimeout == null
                    ? DEFAULT_PUBLISHER_CONFIRM_TIMEOUT : publisherConfirmTimeout;
        }

        public static Dispatch defaults() {
            return new Dispatch(true, 100, DEFAULT_OUTBOX_RETRY_INITIAL_DELAY,
                    DEFAULT_OUTBOX_RETRY_MAX_DELAY, DEFAULT_RELAY_POLL_INTERVAL,
                    DEFAULT_PUBLISHER_CONFIRM_TIMEOUT, 3);
        }

        @AssertTrue(message = "dispatch retry durations must be positive")
        public boolean hasPositiveDurations() {
            return isPositive(outboxRetryInitialDelay)
                    && isPositive(outboxRetryMaxDelay)
                    && isPositive(relayPollInterval)
                    && isPositive(publisherConfirmTimeout)
                    && !outboxRetryMaxDelay.minus(outboxRetryInitialDelay).isNegative();
        }

        private static boolean isPositive(Duration value) {
            return value != null && !value.isZero() && !value.isNegative();
        }
    }

    public record Jobs(
            @DefaultValue("false") boolean enabled,
            @Min(100) @Max(100) @DefaultValue("100") int batchSize,
            @DefaultValue("30d") Duration snapshotRetention,
            @DefaultValue("7d") Duration publishedOutboxRetention,
            @DefaultValue("1h") Duration fixedDelay
    ) {

        public static final int DEFAULT_BATCH_SIZE = 100;
        public static final Duration DEFAULT_SNAPSHOT_RETENTION = Duration.ofDays(30);
        public static final Duration DEFAULT_PUBLISHED_OUTBOX_RETENTION = Duration.ofDays(7);
        public static final Duration DEFAULT_FIXED_DELAY = Duration.ofHours(1);

        public Jobs {
            batchSize = batchSize == 0 ? DEFAULT_BATCH_SIZE : batchSize;
            snapshotRetention = snapshotRetention == null
                    ? DEFAULT_SNAPSHOT_RETENTION : snapshotRetention;
            publishedOutboxRetention = publishedOutboxRetention == null
                    ? DEFAULT_PUBLISHED_OUTBOX_RETENTION : publishedOutboxRetention;
            fixedDelay = fixedDelay == null ? DEFAULT_FIXED_DELAY : fixedDelay;
        }

        public static Jobs defaults() {
            return new Jobs(false, DEFAULT_BATCH_SIZE, DEFAULT_SNAPSHOT_RETENTION,
                    DEFAULT_PUBLISHED_OUTBOX_RETENTION, DEFAULT_FIXED_DELAY);
        }

        @AssertTrue(message = "jobs retention durations and fixed delay must be positive")
        public boolean hasPositiveDurations() {
            return isPositive(snapshotRetention)
                    && isPositive(publishedOutboxRetention)
                    && isPositive(fixedDelay);
        }

        private static boolean isPositive(Duration value) {
            return value != null && !value.isZero() && !value.isNegative();
        }
    }
}
