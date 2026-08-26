package com.finguard.copilot.config;

import java.net.URI;
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
        @Valid @NotNull Jobs jobs
) {

    @ConstructorBinding
    public CopilotProperties(Core core, Embedding embedding, Retrieval retrieval, Dispatch dispatch,
                             Jobs jobs) {
        this.core = core;
        this.embedding = embedding;
        this.retrieval = retrieval;
        this.dispatch = dispatch == null ? Dispatch.defaults() : dispatch;
        this.jobs = jobs == null ? Jobs.defaults() : jobs;
    }

    public CopilotProperties(Core core, Embedding embedding, Retrieval retrieval, Dispatch dispatch) {
        this(core, embedding, retrieval, dispatch, Jobs.defaults());
    }

    public CopilotProperties(Core core, Embedding embedding, Retrieval retrieval) {
        this(core, embedding, retrieval, Dispatch.defaults(), Jobs.defaults());
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
