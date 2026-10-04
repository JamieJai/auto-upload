package com.autoreg.channel.adapter.naver;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import com.autoreg.channel.adapter.ChannelException;

/**
 * 레퍼런스 상품(같은 판매자·같은 품목의 기존 상품) → 신규 등록에 복제할 판매 설정 스냅샷.
 * <p>
 * 남기는 것: 배송·반품, 원산지, 세금, 인증 제외, 구매평, 판매자 코멘트, 미성년자 구매, 카테고리 속성(productAttributes),
 * 자체제작 여부, 브랜드·제조사, 네이버쇼핑 등록 여부.<br>
 * 버리는 것: 상품번호·옵션 ID·bbsSeq 같은 서버 식별자, 상품명·가격·재고·이미지·상세·옵션·고시·태그·판매자코드·제조일자
 * (상품마다 새로 만든다), 할인(상품에 명시될 때만 보낸다. 빈 customerBenefit 은 400 이 난다).
 */
public final class SmartStoreReference {

    /** 특이 상태 상품은 레퍼런스로 쓰지 않는다 (AutoEdit 운영 규칙) */
    static final List<String> UNSAFE_WORDS = List.of("sale", "세일", "품절", "sold out", "soldout", "event", "이벤트",
            "test", "테스트", "sample", "샘플", "당일", "출고", "예약");

    static final List<String> DETAIL_KEYS = List.of("originAreaInfo", "purchaseReviewInfo", "taxType",
            "certificationTargetExcludeContent", "sellerCommentUsable", "minorPurchasable", "productAttributes",
            "itselfProductionProductYn");

    private SmartStoreReference() {}

    /** 레퍼런스로 부적절한 상품명이면 걸린 단어 */
    public static Optional<String> unsafeWord(String name) {
        String n = name == null ? "" : name.toLowerCase(Locale.ROOT);
        return UNSAFE_WORDS.stream().filter(n::contains).findFirst();
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> snapshot(String originProductNo, Map<String, Object> getResponse, boolean allowUnsafe) {
        if (!(getResponse.get("originProduct") instanceof Map<?, ?> rawOrigin)) {
            throw ChannelException.invalid("원상품 응답 형식이 예상과 다릅니다");
        }
        Map<String, Object> origin = (Map<String, Object>) rawOrigin;
        String name = String.valueOf(origin.getOrDefault("name", ""));
        Optional<String> bad = unsafeWord(name);
        if (bad.isPresent() && !allowUnsafe) {
            throw ChannelException.invalid("레퍼런스 상품명에 '" + bad.get() + "' 가 있습니다 (" + name
                    + "). 특이 상태 상품은 판매 설정이 다를 수 있어 일반 상품을 고르세요");
        }
        if (!(origin.get("deliveryInfo") instanceof Map<?, ?>)) {
            throw ChannelException.invalid("레퍼런스에 배송 정보가 없습니다");
        }
        Map<String, Object> snap = new LinkedHashMap<>();
        snap.put("sourceOriginProductNo", originProductNo);
        snap.put("sourceName", name);
        snap.put("leafCategoryId", origin.get("leafCategoryId"));
        snap.put("deliveryInfo", origin.get("deliveryInfo"));

        Map<String, Object> detail = new LinkedHashMap<>();
        if (origin.get("detailAttribute") instanceof Map<?, ?> d) {
            for (String k : DETAIL_KEYS) {
                if (d.get(k) != null) {
                    detail.put(k, d.get(k));
                }
            }
            if (d.get("naverShoppingSearchInfo") instanceof Map<?, ?> s) {
                Map<String, Object> keep = new LinkedHashMap<>();
                for (String k : List.of("brandName", "manufacturerName")) {
                    if (s.get(k) != null) {
                        keep.put(k, s.get(k));
                    }
                }
                detail.put("naverShoppingSearchInfo", keep);
            }
        }
        if (!(detail.get("originAreaInfo") instanceof Map<?, ?>)) {
            throw ChannelException.invalid("레퍼런스에 원산지 정보가 없습니다");
        }
        snap.put("detailAttribute", detail);

        Map<String, Object> channel = new LinkedHashMap<>();
        if (getResponse.get("smartstoreChannelProduct") instanceof Map<?, ?> ch) {
            for (String k : List.of("naverShoppingRegistration", "storeKeepExclusiveProduct")) {
                if (ch.get(k) != null) {
                    channel.put(k, ch.get(k));
                }
            }
        }
        snap.put("smartstoreChannelProduct", channel);
        return snap;
    }

    /** 품목 레퍼런스가 없을 때: 채널 계정 settings 의 예전 템플릿 값을 같은 모양으로 맞춘다 */
    public static Map<String, Object> fromAccountSettings(Map<String, Object> settings) {
        Map<String, Object> snap = new LinkedHashMap<>();
        snap.put("deliveryInfo", settings.get("deliveryInfo"));
        Map<String, Object> detail = new LinkedHashMap<>();
        for (String k : List.of("originAreaInfo", "taxType", "minorPurchasable", "certificationTargetExcludeContent",
                "naverShoppingSearchInfo")) {
            if (settings.get(k) != null) {
                detail.put(k, settings.get(k));
            }
        }
        snap.put("detailAttribute", detail);
        Map<String, Object> channel = new LinkedHashMap<>();
        if (settings.get("naverShoppingRegistration") != null) {
            channel.put("naverShoppingRegistration", settings.get("naverShoppingRegistration"));
        }
        snap.put("smartstoreChannelProduct", channel);
        return snap;
    }
}
