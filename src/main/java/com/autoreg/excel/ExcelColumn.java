package com.autoreg.excel;

import java.util.Arrays;
import java.util.Optional;

/** 엑셀 템플릿 '상품' 시트의 고정 컬럼. 헤더 이름으로 찾으므로 순서는 바뀌어도 된다. */
public enum ExcelColumn {

    CODE("상품코드", true, "SS2609001"),
    CATEGORY("카테고리", true, "원피스"),
    SALE_PRICE("판매가", true, "39000"),
    COLORS("색상", true, "블랙, 아이보리"),
    SIZES("사이즈", true, "S, M, L"),
    STOCK("재고(옵션당)", true, "10"),
    NAME("상품명", false, "비워 두면 AI 가 생성"),
    DESCRIPTION("상세설명", false, "비워 두면 AI 가 생성"),
    KEYWORDS("검색키워드", false, "린넨원피스, 여름원피스"),
    MATERIAL("소재·혼용률", true, "린넨 55%, 레이온 45%"),
    MANUFACTURER("제조자/수입자", false, "비워 두면 판매자 기본값"),
    ORIGIN_COUNTRY("제조국", false, "대한민국"),
    WASH_CARE("세탁방법", false, "드라이클리닝 권장"),
    QUALITY_ASSURANCE("품질보증기준", false, ""),
    AS_MANAGER("A/S책임자", false, ""),
    AS_PHONE("A/S전화번호", false, ""),
    MANUFACTURED_YM("제조연월", false, "2026-09"),
    KC_CERTIFICATION("KC인증", false, "");

    private final String header;
    private final boolean required;
    private final String example;

    ExcelColumn(String header, boolean required, String example) {
        this.header = header;
        this.required = required;
        this.example = example;
    }

    public String header() {
        return header;
    }

    /** 템플릿에 표시하는 헤더. 필수는 * 를 붙인다 */
    public String displayHeader() {
        return required ? header + "*" : header;
    }

    public boolean required() {
        return required;
    }

    public String example() {
        return example;
    }

    public static Optional<ExcelColumn> byHeader(String raw) {
        String h = normalize(raw);
        return Arrays.stream(values()).filter(c -> normalize(c.header).equals(h)).findFirst();
    }

    static String normalize(String s) {
        return s == null ? "" : s.replace("*", "").replaceAll("\\s+", "").trim();
    }
}
