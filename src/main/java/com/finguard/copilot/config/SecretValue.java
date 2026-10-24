package com.finguard.copilot.config;

import java.util.Objects;

/**
 * A configuration value whose contents must not appear in diagnostics.
 */
public final class SecretValue {

    private static final SecretValue EMPTY = new SecretValue("");

    private final String value;

    public SecretValue(String value) {
        this.value = Objects.requireNonNull(value, "value must not be null");
    }

    public static SecretValue empty() {
        return EMPTY;
    }

    public String value() {
        return value;
    }

    public boolean isPresent() {
        return !value.isBlank();
    }

    @Override
    public String toString() {
        return "[REDACTED]";
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof SecretValue that)) {
            return false;
        }
        return value.equals(that.value);
    }

    @Override
    public int hashCode() {
        return value.hashCode();
    }
}
