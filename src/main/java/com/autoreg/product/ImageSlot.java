package com.autoreg.product;

import java.util.Arrays;
import java.util.Optional;

/** 이미지 슬롯과 등록에 필요한 최소 장수. DB 에는 소문자 이름으로 저장한다. */
public enum ImageSlot {

    MAIN("main", 1, "대표 이미지"),
    SUB("sub", 2, "연출컷"),
    DETAIL("detail", 2, "원단·디테일"),
    SIZE("size", 0, "실측 사이즈표");

    private final String value;
    private final int minCount;
    private final String label;

    ImageSlot(String value, int minCount, String label) {
        this.value = value;
        this.minCount = minCount;
        this.label = label;
    }

    public String value() {
        return value;
    }

    public int minCount() {
        return minCount;
    }

    public String label() {
        return label;
    }

    public static Optional<ImageSlot> of(String value) {
        return Arrays.stream(values()).filter(s -> s.value.equalsIgnoreCase(value)).findFirst();
    }
}
