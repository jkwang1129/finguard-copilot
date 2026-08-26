package com.finguard.copilot.coreclient.port;

public final class CoreClientException extends RuntimeException {

    public enum Kind {
        UNAUTHENTICATED, FORBIDDEN, NOT_FOUND, UNAVAILABLE, INVALID_RESPONSE
    }

    private final Kind kind;

    public CoreClientException(Kind kind, String message, Throwable cause) {
        super(message, cause);
        this.kind = java.util.Objects.requireNonNull(kind);
    }

    public Kind kind() {
        return kind;
    }
}
