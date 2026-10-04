package com.autoreg.llm;

import java.time.Duration;

public class LlmException extends RuntimeException {

    private final boolean retryable;
    private final Duration retryAfter;

    public LlmException(String message, boolean retryable, Duration retryAfter) {
        super(message);
        this.retryable = retryable;
        this.retryAfter = retryAfter;
    }

    public boolean retryable() {
        return retryable;
    }

    public Duration retryAfter() {
        return retryAfter;
    }
}
