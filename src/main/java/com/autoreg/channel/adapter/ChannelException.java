package com.autoreg.channel.adapter;

import java.time.Duration;
import java.util.Map;

/**
 * 채널 API 실패. retryable: 타임아웃·5xx·429 / 아니면: 400 유효성 실패 (사용자가 고쳐야 함).
 * message 는 사용자 안내 문구로 쓰이므로 키·토큰을 넣지 않는다.
 */
public class ChannelException extends RuntimeException {

    private final boolean retryable;
    private final Duration retryAfter;
    private final Map<String, Object> rawResponse;

    public ChannelException(String message, boolean retryable, Duration retryAfter, Map<String, Object> rawResponse) {
        super(message);
        this.retryable = retryable;
        this.retryAfter = retryAfter;
        this.rawResponse = rawResponse;
    }

    public static ChannelException invalid(String message) {
        return new ChannelException(message, false, null, null);
    }

    public boolean retryable() {
        return retryable;
    }

    public Duration retryAfter() {
        return retryAfter;
    }

    public Map<String, Object> rawResponse() {
        return rawResponse;
    }
}
