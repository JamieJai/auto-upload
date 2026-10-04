package com.autoreg.common;

import java.util.List;

import com.autoreg.product.validation.ValidationIssue;

/** 상품 검증 실패. 422 와 함께 항목 목록을 돌려준다 */
public class ValidationFailedException extends RuntimeException {

    private final List<ValidationIssue> issues;

    public ValidationFailedException(String message, List<ValidationIssue> issues) {
        super(message);
        this.issues = issues;
    }

    public List<ValidationIssue> issues() {
        return issues;
    }
}
