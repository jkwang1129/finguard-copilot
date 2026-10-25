package com.finguard.copilot.context;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

public record PromptTemplate(String version) {

    private static final String RESOURCE_PREFIX = "/prompts/";
    private static final String RESOURCE_SUFFIX = ".txt";

    public PromptTemplate {
        if (version == null || version.isBlank() || !version.matches("[A-Za-z0-9][A-Za-z0-9._-]*")) {
            throw new IllegalArgumentException("prompt version is invalid");
        }
    }

    public String render() {
        String resource = RESOURCE_PREFIX + version + RESOURCE_SUFFIX;
        try (InputStream input = PromptTemplate.class.getResourceAsStream(resource)) {
            if (input == null) {
                throw new IllegalArgumentException("prompt template is unavailable: " + version);
            }
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new IllegalStateException("prompt template could not be read: " + version, exception);
        }
    }
}
