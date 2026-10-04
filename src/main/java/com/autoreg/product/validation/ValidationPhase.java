package com.autoreg.product.validation;

public enum ValidationPhase {
    /** LLM 호출 전. 입력값(가격·옵션·실측·고시정보·이미지)만 본다. 문구는 비어 있어도 된다 */
    INPUT,
    /** 승인·등록 전. 문구까지 채워져 있어야 한다 */
    READY
}
