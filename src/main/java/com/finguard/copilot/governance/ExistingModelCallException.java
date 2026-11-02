package com.finguard.copilot.governance;

/** Never converts an existing durable call slot into another provider request. */
public final class ExistingModelCallException extends RuntimeException {
    public ExistingModelCallException() {super("model call already reserved");}
}
