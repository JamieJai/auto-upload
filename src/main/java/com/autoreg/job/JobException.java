package com.autoreg.job;

import java.time.Duration;

/** 작업 실패. retryable 이면 자동 재시도, 아니면 사용자가 고쳐야 한다 */
public class JobException extends RuntimeException {

    private final boolean retryable;
    private final Duration retryAfter;

    private JobException(String message, boolean retryable, Duration retryAfter, Throwable cause) {
        super(message, cause);
        this.retryable = retryable;
        this.retryAfter = retryAfter;
    }

    public static JobException retryable(String message) {
        return new JobException(message, true, null, null);
    }

    public static JobException retryable(String message, Duration retryAfter) {
        return new JobException(message, true, retryAfter, null);
    }

    public static JobException retryable(String message, Throwable cause) {
        return new JobException(message, true, null, cause);
    }

    public static JobException invalid(String message) {
        return new JobException(message, false, null, null);
    }

    public boolean retryable() {
        return retryable;
    }

    public Duration retryAfter() {
        return retryAfter;
    }
}
