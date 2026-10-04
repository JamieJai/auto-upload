package com.autoreg.product;

import java.util.EnumSet;
import java.util.Set;

public enum ProductStatus {
    DRAFT,
    /** 필수값·이미지 누락 또는 반려. 사용자가 보완한다 */
    NEEDS_INPUT,
    GENERATING,
    PENDING_APPROVAL,
    APPROVED,
    CANCELLED;

    private static final Set<ProductStatus> EDITABLE = EnumSet.of(DRAFT, NEEDS_INPUT, PENDING_APPROVAL);

    /** 생성 중·승인 후에는 입력을 바꾸지 않는다. 바꾸려면 상태를 되돌린다 */
    public boolean editable() {
        return EDITABLE.contains(this);
    }
}
