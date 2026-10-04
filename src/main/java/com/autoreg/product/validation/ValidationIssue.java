package com.autoreg.product.validation;

/**
 * 검증 실패 1건.
 *
 * @param field   화면에서 강조할 필드 경로 (예: "options[2].size", "images.detail")
 * @param code    기계가 읽는 코드 (예: "REQUIRED", "IMAGE_SLOT_SHORT")
 * @param message 사용자 안내 문구
 */
public record ValidationIssue(String field, String code, String message) {}
