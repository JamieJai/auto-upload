package com.autoreg.tenant;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import lombok.Data;
import lombok.NoArgsConstructor;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * 특성: 이 쇼핑몰은 상품을 어떻게 올리는가. 기본값은 특성을 정하기 전의 동작과 같다.
 * <p>
 * 적용 위치: 문구·태그 → AI 지시(CopyGenerator) + 제출 전 검증(ProductValidator),
 * 대소문자·앞뒤 문구 → 등록 본문에서 강제 변환, 이미지 순서·상세 구성·할인·전시 → 등록 본문(SmartStorePayload).
 */
@Data
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class StyleProfile {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {};

    public enum Case { AS_IS, LOWER, UPPER;

        public String apply(String s) {
            if (s == null) {
                return null;
            }
            return switch (this) {
                case LOWER -> s.toLowerCase(Locale.ROOT);
                case UPPER -> s.toUpperCase(Locale.ROOT);
                case AS_IS -> s;
            };
        }
    }

    /** 상세페이지 블록 */
    public enum DetailBlock { TEXT, MAIN_IMAGES, SUB_IMAGES, DETAIL_IMAGES, SIZE_TABLE, SIZE_IMAGES }

    /** 추가이미지(대표 옆 갤러리)를 채우는 순서 */
    public enum ImageGroup { MAIN_REST, SUB, DETAIL, SIZE }

    private Copy copy = new Copy();
    private Tags tags = new Tags();
    private Options options = new Options();
    private Images images = new Images();
    private Detail detail = new Detail();
    private Registration registration = new Registration();

    @Data
    @NoArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Copy {
        /** 상품명 앞뒤에 붙일 말 (예: "[브랜드] ", " (2 color)"). 등록 때 항상 붙인다 */
        private String namePrefix = "";
        private String nameSuffix = "";
        /** 앞뒤 말까지 포함한 상품명 최대 글자 수 (스마트스토어 한도 100) */
        private int nameMaxLength = 100;
        private int descriptionMinLength = 300;
        private int descriptionMaxLength = 800;
        /** 상품명·상세설명에 쓰면 안 되는 말. 들어 있으면 제출이 막힌다 */
        private List<String> bannedWords = new ArrayList<>();
        /** AI 에게 추가로 줄 지시 (상품명 스타일, 상세설명 구성 등 자유 문장) */
        private String instructions = "";
    }

    @Data
    @NoArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Tags {
        /** 최소·최대 개수. 같으면 정확히 그 개수 */
        private int min = 0;
        private int max = 10;
        private Case textCase = Case.AS_IS;
        /** 앞쪽 태그 구성 규칙 (예: "앞 3개는 거래처명 + 핵심 특징") */
        private String leadingRule = "";
    }

    @Data
    @NoArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Options {
        private String groupName1 = "색상";
        private String groupName2 = "사이즈";
        private Case colorCase = Case.AS_IS;
        private Case sizeCase = Case.AS_IS;
        /** 사이즈 표기 바꾸기 (예: {"FREE": "free", "F": "free"}). 대소문자 변환보다 먼저 적용 */
        private Map<String, String> sizeAliases = new LinkedHashMap<>();
    }

    @Data
    @NoArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Images {
        /** 추가이미지 채우는 순서. 앞 그룹부터 채우고 최대 장수에서 자른다 */
        private List<ImageGroup> optionalOrder = new ArrayList<>(List.of(ImageGroup.MAIN_REST, ImageGroup.SUB));
        private int maxOptional = 9;
    }

    @Data
    @NoArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Detail {
        private List<DetailBlock> blocks = new ArrayList<>(
                List.of(DetailBlock.TEXT, DetailBlock.DETAIL_IMAGES, DetailBlock.SIZE_TABLE, DetailBlock.SIZE_IMAGES));
    }

    @Data
    @NoArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Registration {
        /** 등록 직후 전시 상태: SUSPENSION(전시 중지) 또는 ON */
        private String displayStatus = "SUSPENSION";
        /** 즉시할인. 0 이면 보내지 않는다 (빈 customerBenefit 은 400) */
        private int discountValue = 0;
        /** PERCENT 또는 WON */
        private String discountUnit = "PERCENT";
    }

    public static StyleProfile from(Map<String, Object> stored) {
        if (stored == null || stored.isEmpty()) {
            return new StyleProfile();
        }
        return JSON.convertValue(stored, StyleProfile.class);
    }

    /** 특성을 정하기 전 채널 계정 settings 에 두던 옵션·전시 설정을 특성 모양으로 */
    public static StyleProfile legacy(Map<String, Object> accountSettings) {
        StyleProfile s = new StyleProfile();
        if (accountSettings == null) {
            return s;
        }
        if (accountSettings.get("optionGroupName1") != null) {
            s.options.groupName1 = String.valueOf(accountSettings.get("optionGroupName1"));
        }
        if (accountSettings.get("optionGroupName2") != null) {
            s.options.groupName2 = String.valueOf(accountSettings.get("optionGroupName2"));
        }
        if (Boolean.parseBoolean(String.valueOf(accountSettings.getOrDefault("lowercaseOptionValues", false)))) {
            s.options.colorCase = Case.LOWER;
            s.options.sizeCase = Case.LOWER;
        }
        if (accountSettings.get("displayStatus") != null) {
            s.registration.displayStatus = String.valueOf(accountSettings.get("displayStatus"));
        }
        return s;
    }

    public Map<String, Object> toMap() {
        return JSON.convertValue(this, MAP);
    }

    /** 저장 전 범위 검사. 잘못되면 IllegalArgumentException (400) */
    public StyleProfile checked() {
        if (tags.min < 0 || tags.max < 1 || tags.max > 10 || tags.min > tags.max) {
            throw new IllegalArgumentException("태그 개수는 0 ≤ 최소 ≤ 최대 ≤ 10 이어야 합니다");
        }
        if (copy.nameMaxLength < 10 || copy.nameMaxLength > 100) {
            throw new IllegalArgumentException("상품명 최대 길이는 10~100 이어야 합니다");
        }
        if (copy.descriptionMinLength < 0 || copy.descriptionMaxLength < copy.descriptionMinLength || copy.descriptionMaxLength > 5000) {
            throw new IllegalArgumentException("상세설명 길이 범위가 올바르지 않습니다");
        }
        if (images.maxOptional < 0 || images.maxOptional > 9) {
            throw new IllegalArgumentException("추가이미지는 0~9장입니다");
        }
        if (!List.of("SUSPENSION", "ON").contains(registration.displayStatus)) {
            throw new IllegalArgumentException("전시 상태는 SUSPENSION 또는 ON 입니다");
        }
        if (registration.discountValue < 0 || !List.of("PERCENT", "WON").contains(registration.discountUnit)
                || ("PERCENT".equals(registration.discountUnit) && registration.discountValue >= 100)) {
            throw new IllegalArgumentException("할인 값이 올바르지 않습니다");
        }
        if (detail.blocks.isEmpty()) {
            throw new IllegalArgumentException("상세페이지 구성은 한 블록 이상이어야 합니다");
        }
        if (options.groupName1.isBlank() || options.groupName2.isBlank()) {
            throw new IllegalArgumentException("옵션 그룹명이 비어 있습니다");
        }
        return this;
    }

    /** 앞뒤 말을 붙인 최종 상품명. 이미 붙어 있으면 다시 붙이지 않는다 */
    public String finalName(String name) {
        if (name == null || name.isBlank()) {
            return name;
        }
        String n = name.strip();
        if (!copy.namePrefix.isEmpty() && !n.startsWith(copy.namePrefix.strip())) {
            n = copy.namePrefix + n;
        }
        if (!copy.nameSuffix.isEmpty() && !n.endsWith(copy.nameSuffix.strip())) {
            n = n + copy.nameSuffix;
        }
        return n;
    }

    public String color(String c) {
        return options.colorCase.apply(c);
    }

    public String size(String s) {
        if (s == null) {
            return null;
        }
        String aliased = options.sizeAliases.getOrDefault(s, options.sizeAliases.getOrDefault(s.toUpperCase(Locale.ROOT), s));
        return options.sizeCase.apply(aliased);
    }

    public String tag(String t) {
        return tags.textCase.apply(t);
    }
}
