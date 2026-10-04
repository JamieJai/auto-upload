package com.autoreg.product;

import java.util.Arrays;
import java.util.Optional;
import java.util.function.BiConsumer;
import java.util.function.Function;

/**
 * 상품정보제공고시(의류) 항목. 키는 판매자 기본값(notice_defaults)의 키와 같다.
 * required 인 항목이 비어 있으면 등록 전 검증에서 막힌다.
 */
public enum NoticeField {

    MATERIAL("material", "제품 소재·혼용률", true, Product::getMaterial, Product::setMaterial),
    MANUFACTURER("manufacturer", "제조자/수입자", true, Product::getManufacturer, Product::setManufacturer),
    ORIGIN_COUNTRY("origin_country", "제조국", true, Product::getOriginCountry, Product::setOriginCountry),
    WASH_CARE("wash_care", "세탁방법 및 취급시 주의사항", true, Product::getWashCare, Product::setWashCare),
    QUALITY_ASSURANCE("quality_assurance", "품질보증기준", true, Product::getQualityAssurance, Product::setQualityAssurance),
    AS_MANAGER("as_manager", "A/S 책임자", true, Product::getAsManager, Product::setAsManager),
    AS_PHONE("as_phone", "A/S 전화번호", true, Product::getAsPhone, Product::setAsPhone),
    MANUFACTURED_YM("manufactured_ym", "제조연월", false, Product::getManufacturedYm, Product::setManufacturedYm),
    KC_CERTIFICATION("kc_certification", "KC 인증정보", false, Product::getKcCertification, Product::setKcCertification);

    private final String key;
    private final String label;
    private final boolean required;
    private final Function<Product, String> getter;
    private final BiConsumer<Product, String> setter;

    NoticeField(String key, String label, boolean required, Function<Product, String> getter,
            BiConsumer<Product, String> setter) {
        this.key = key;
        this.label = label;
        this.required = required;
        this.getter = getter;
        this.setter = setter;
    }

    public String key() {
        return key;
    }

    public String label() {
        return label;
    }

    public boolean required() {
        return required;
    }

    public String get(Product p) {
        return getter.apply(p);
    }

    public void set(Product p, String value) {
        setter.accept(p, value);
    }

    public static Optional<NoticeField> byKey(String key) {
        return Arrays.stream(values()).filter(f -> f.key.equals(key)).findFirst();
    }
}
