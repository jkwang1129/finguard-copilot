package com.finguard.copilot.config;

import java.net.URI;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("finguard.copilot")
public record CopilotProperties(
        @Valid @NotNull Core core,
        @Valid @NotNull Embedding embedding,
        @Valid @NotNull Retrieval retrieval
) {

    public record Core(@NotNull URI baseUrl) {

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
    }

    public record Embedding(@Min(4) @Max(4096) int dimensions) {
    }

    public record Retrieval(@Min(1) @Max(100) int topK) {
    }
}
