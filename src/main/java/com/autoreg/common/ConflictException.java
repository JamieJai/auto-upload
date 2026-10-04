package com.autoreg.common;

/** 현재 상태에서 허용되지 않는 요청. HTTP 409 로 응답한다. */
public class ConflictException extends RuntimeException {

    public ConflictException(String message) {
        super(message);
    }
}
