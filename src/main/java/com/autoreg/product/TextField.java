package com.autoreg.product;

/** 직접 입력하거나 AI 로 생성할 수 있는 문구 필드. AI 생성 금지 항목은 여기 없다. */
public enum TextField {
    NAME,
    DESCRIPTION,
    SEARCH_KEYWORDS,
    OPTION_DISPLAY;

    public enum Source { MANUAL, AI }
}
