package com.finguard.copilot.observability;

import java.util.Set;

public final class TelemetryVocabulary {
    private TelemetryVocabulary() {}
    public static String provider(String value) {
        return value!=null && Set.of("deepseek","qwen","zhipu","stub").contains(value)?value:"OTHER";
    }
    public static String outcome(String value) {
        return value!=null && Set.of("SUCCESS","FAILURE","TIMEOUT","RATE_LIMITED").contains(value)?value:"OTHER";
    }
    public static String model(String value) {
        return value!=null && Set.of("deepseek-flash","deepseek-chat","qwen-plus","glm-4-flash","local-test-stub").contains(value)?value:"OTHER";
    }
}
