package com.finguard.copilot.common.error;

import java.util.Objects;

public class CopilotException extends RuntimeException {

    private final ErrorCode code;

    public CopilotException(ErrorCode code, String message) {
        super(Objects.requireNonNull(message, "message must not be null"));
        this.code = Objects.requireNonNull(code, "code must not be null");
    }

    public ErrorCode code() {
        return code;
    }
}
