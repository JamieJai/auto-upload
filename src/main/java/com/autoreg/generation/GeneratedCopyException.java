package com.autoreg.generation;

/** LLM 이 형식에 맞지 않는 문구를 돌려줌. 다시 시도하면 나아질 수 있다 */
public class GeneratedCopyException extends RuntimeException {

    public GeneratedCopyException(String message) {
        super(message);
    }
}
