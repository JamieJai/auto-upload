package com.autoreg.channel.adapter.naver;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.web.util.HtmlUtils;

import com.autoreg.channel.adapter.ChannelAdapter.UploadedImage;
import com.autoreg.channel.adapter.ChannelException;
import com.autoreg.product.ImageSlot;
import com.autoreg.product.Product;
import com.autoreg.product.ProductMeasurement;
import com.autoreg.product.ProductOption;
import com.autoreg.product.validation.ProductValidator;
import com.autoreg.tenant.StyleProfile;

/**
 * 상품 → 스마트스토어 등록 본문 (POST /v2/products).
 * 판매자 공통 값(배송·원산지·브랜드 등)은 채널 계정 settings 에서 가져온다. 기존 상품을 템플릿으로 가져와 채운다.
 * <pre>
 * settings 키:
 *   dryRun (기본 true)            등록 호출 직전에 멈추고 본문만 남긴다
 *   displayStatus (기본 SUSPENSION) 등록 후 전시 상태. ON 이어야 고객에게 보인다
 *   deliveryInfo                   배송·반품 설정 (필수, 템플릿에서)
 *   originAreaInfo                 원산지 코드 (필수, 템플릿에서)
 *   naverShoppingSearchInfo        brandName, manufacturerName
 *   taxType, minorPurchasable, certificationTargetExcludeContent, naverShoppingRegistration
 * </pre>
 */
public final class SmartStorePayload {

    static final int MAX_OPTIONAL_IMAGES = 9;
    static final int MAX_TAGS = 10;

    private SmartStorePayload() {}

    public static boolean dryRun(Map<String, Object> settings) {
        Object v = settings.get("dryRun");
        return v == null || Boolean.parseBoolean(String.valueOf(v));
    }

    /** 레퍼런스 없이 (예전 방식: 채널 계정 settings 템플릿) */
    public static Map<String, Object> build(Product p, String categoryId, List<UploadedImage> images, Map<String, Object> settings) {
        return build(p, categoryId, images, settings, null);
    }

    /**
     * reference: 품목 레퍼런스 스냅샷(SmartStoreReference.snapshot). 판매 설정은 레퍼런스를 그대로 쓰고,
     * 상품마다 다른 값(이름·가격·재고·이미지·상세·옵션·고시·태그·판매자코드·제조일자·모델명)만 새로 채운다.
     */
    public static Map<String, Object> build(Product p, String categoryId, List<UploadedImage> images, Map<String, Object> settings,
            Map<String, Object> reference) {
        return build(p, categoryId, images, settings, reference, StyleProfile.legacy(settings));
    }

    /** style: 판매자 특성. 상품명 앞뒤 문구·옵션 표기·태그·이미지 순서·상세 구성·할인·전시 상태를 정한다 */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> build(Product p, String categoryId, List<UploadedImage> images, Map<String, Object> settings,
            Map<String, Object> reference, StyleProfile style) {
        Map<String, Object> base = reference != null ? reference : SmartStoreReference.fromAccountSettings(settings);
        Map<String, Object> baseDetail = base.get("detailAttribute") instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
        List<String> missing = new ArrayList<>();
        if (!(base.get("deliveryInfo") instanceof Map<?, ?>)) {
            missing.add("deliveryInfo");
        }
        if (!(baseDetail.get("originAreaInfo") instanceof Map<?, ?>)) {
            missing.add("originAreaInfo");
        }
        if (!missing.isEmpty()) {
            throw ChannelException.invalid("판매 설정(" + String.join(", ", missing) + ")이 없습니다. 판매자 관리 → 카테고리 매핑에서 "
                    + "이 품목의 레퍼런스 상품을 가져오세요 (또는 채널 계정 템플릿 → 기존 상품에서 템플릿 가져오기)");
        }
        Object refLeaf = base.get("leafCategoryId");
        if (refLeaf != null && !String.valueOf(refLeaf).equals(categoryId)) {
            throw ChannelException.invalid("레퍼런스 카테고리(" + refLeaf + ")와 매핑 카테고리(" + categoryId
                    + ")가 다릅니다. 같은 품목의 레퍼런스를 쓰세요");
        }
        List<String> mains = urls(images, ImageSlot.MAIN);
        if (mains.isEmpty()) {
            throw ChannelException.invalid("대표 이미지가 없습니다");
        }
        List<String> optional = new ArrayList<>();
        for (StyleProfile.ImageGroup g : style.getImages().getOptionalOrder()) {
            switch (g) {
                case MAIN_REST -> optional.addAll(mains.subList(1, mains.size()));
                case SUB -> optional.addAll(urls(images, ImageSlot.SUB));
                case DETAIL -> optional.addAll(urls(images, ImageSlot.DETAIL));
                case SIZE -> optional.addAll(urls(images, ImageSlot.SIZE));
            }
        }
        String name = style.finalName(p.getName());

        Map<String, Object> origin = new LinkedHashMap<>();
        origin.put("statusType", "SALE");
        origin.put("saleType", "NEW");
        origin.put("leafCategoryId", categoryId);
        origin.put("name", name);
        origin.put("detailContent", detailHtml(p, images, style));
        origin.put("images", Map.of(
                "representativeImage", Map.of("url", mains.get(0)),
                "optionalImages", optional.stream().distinct().limit(Math.min(MAX_OPTIONAL_IMAGES, style.getImages().getMaxOptional()))
                        .map(u -> Map.of("url", u)).toList()));
        origin.put("salePrice", p.getSalePrice());
        origin.put("stockQuantity", p.getOptions().stream().mapToInt(ProductOption::getStock).sum());
        origin.put("deliveryInfo", base.get("deliveryInfo"));
        origin.put("detailAttribute", detailAttribute(p, baseDetail, style));
        StyleProfile.Registration reg = style.getRegistration();
        if (reg.getDiscountValue() > 0) {
            // 할인이 있을 때만 보낸다. 빈 customerBenefit 은 400
            origin.put("customerBenefit", Map.of("immediateDiscountPolicy", Map.of("discountMethod",
                    Map.of("value", reg.getDiscountValue(), "unitType", reg.getDiscountUnit()))));
        }

        Map<String, Object> channel = new LinkedHashMap<>();
        if (base.get("smartstoreChannelProduct") instanceof Map<?, ?> ch) {
            ((Map<String, Object>) ch).forEach(channel::put);
        }
        channel.remove("bbsSeq");
        channel.put("channelProductName", name);
        channel.putIfAbsent("naverShoppingRegistration", true);
        channel.putIfAbsent("storeKeepExclusiveProduct", false);
        channel.put("channelProductDisplayStatusType", reg.getDisplayStatus());

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("originProduct", origin);
        body.put("smartstoreChannelProduct", channel);
        return body;
    }

    private static Map<String, Object> detailAttribute(Product p, Map<String, Object> ref, StyleProfile style) {
        Map<String, Object> d = new LinkedHashMap<>(ref);
        Map<String, Object> search = new LinkedHashMap<>();
        if (ref.get("naverShoppingSearchInfo") instanceof Map<?, ?> m) {
            m.forEach((k, v) -> {
                if (!"modelId".equals(k)) {
                    search.put(String.valueOf(k), v);
                }
            });
        }
        search.putIfAbsent("manufacturerName", p.getManufacturer());
        search.put("modelName", p.getName());
        search.put("catalogMatchingYn", false);
        d.put("naverShoppingSearchInfo", search);
        d.put("afterServiceInfo", Map.of(
                "afterServiceTelephoneNumber", digits(p.getAsPhone()),
                "afterServiceGuideContent", p.getAsManager()));
        d.put("sellerCodeInfo", Map.of("sellerManagementCode", p.getCode()));
        d.put("optionInfo", optionInfo(p, style));
        d.putIfAbsent("taxType", "TAX");
        d.putIfAbsent("minorPurchasable", true);
        d.putIfAbsent("certificationTargetExcludeContent", Map.of("kcCertifiedProductExclusionYn", "TRUE"));
        d.put("productInfoProvidedNotice", Map.of("productInfoProvidedNoticeType", "WEAR", "wear", wearNotice(p, style)));
        String ym = p.getManufacturedYm();
        if (ym != null && ym.matches("\\d{4}-\\d{2}")) {
            d.put("manufactureDate", ym + "-01");
        } else {
            d.remove("manufactureDate");
        }
        // 태그는 각각 UTF-8 30바이트 미만이어야 한다 (넘으면 400 MaxByteLength). 검증을 통과했어도 한 번 더 거른다
        List<Map<String, String>> tags = p.getSearchKeywords().stream().map(style::tag).filter(ProductValidator::tagFits).distinct()
                .limit(Math.min(MAX_TAGS, style.getTags().getMax())).map(k -> Map.of("text", k)).toList();
        if (tags.isEmpty()) {
            d.remove("seoInfo");
        } else {
            d.put("seoInfo", Map.of("sellerTags", tags));
        }
        return d;
    }

    /** 옵션 그룹명·색상·사이즈 표기는 특성을 따른다 */
    private static Map<String, Object> optionInfo(Product p, StyleProfile style) {
        List<Map<String, Object>> combos = new ArrayList<>();
        for (ProductOption o : p.getOptions()) {
            Map<String, Object> c = new LinkedHashMap<>();
            c.put("optionName1", style.color(displayColor(o)));
            c.put("optionName2", style.size(o.getSize()));
            c.put("stockQuantity", o.getStock());
            c.put("price", o.getExtraPrice());
            c.put("usable", true);
            if (o.getSku() != null) {
                c.put("sellerManagerCode", o.getSku());
            }
            combos.add(c);
        }
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("optionCombinationSortType", "CREATE");
        info.put("optionCombinationGroupNames", Map.of(
                "optionGroupName1", style.getOptions().getGroupName1(),
                "optionGroupName2", style.getOptions().getGroupName2()));
        info.put("optionCombinations", combos);
        info.put("useStockManagement", true);
        info.put("optionDeliveryAttributes", List.of());
        return info;
    }

    /** 상품정보제공고시 의류(WEAR). 값은 모두 입력값 그대로 (AI 생성 금지 항목) */
    private static Map<String, Object> wearNotice(Product p, StyleProfile style) {
        Set<String> colors = p.getOptions().stream().map(o -> style.color(displayColor(o))).collect(Collectors.toCollection(LinkedHashSet::new));
        Set<String> sizes = p.getOptions().stream().map(o -> style.size(o.getSize())).collect(Collectors.toCollection(LinkedHashSet::new));
        Map<String, Object> w = new LinkedHashMap<>();
        w.put("material", p.getMaterial());
        w.put("color", String.join("/", colors));
        w.put("size", String.join("/", sizes));
        w.put("manufacturer", p.getManufacturer());
        w.put("caution", p.getWashCare());
        w.put("packDate", blank(p.getManufacturedYm()) ? "상세설명참조" : p.getManufacturedYm());
        // 의류 고시의 나머지 항목. 레퍼런스 성공 요청과 같은 값을 쓴다
        w.put("warrantyPolicy", p.getQualityAssurance());
        w.put("afterServiceDirector", p.getAsManager() + " " + p.getAsPhone());
        return w;
    }

    static String detailHtml(Product p, List<UploadedImage> images) {
        return detailHtml(p, images, new StyleProfile());
    }

    /** 상세설명 HTML. 블록 순서는 특성(detail.blocks)을 따른다. 기본: 문구 → 디테일컷 → 실측표 → 사이즈표 이미지 */
    static String detailHtml(Product p, List<UploadedImage> images, StyleProfile style) {
        StringBuilder sb = new StringBuilder("<div style=\"max-width:860px;margin:0 auto;text-align:center;font-size:15px;line-height:1.8;color:#222\">");
        for (StyleProfile.DetailBlock b : style.getDetail().getBlocks()) {
            switch (b) {
                case TEXT -> {
                    if (!blank(p.getDescription())) {
                        for (String para : p.getDescription().strip().split("\\n\\s*\\n")) {
                            sb.append("<p style=\"margin:0 0 18px\">").append(HtmlUtils.htmlEscape(para.strip()).replace("\n", "<br>")).append("</p>");
                        }
                    }
                }
                case MAIN_IMAGES -> images(sb, urls(images, ImageSlot.MAIN));
                case SUB_IMAGES -> images(sb, urls(images, ImageSlot.SUB));
                case DETAIL_IMAGES -> images(sb, urls(images, ImageSlot.DETAIL));
                case SIZE_TABLE -> {
                    if (!p.getMeasurements().isEmpty()) {
                        sb.append(sizeTable(p, style));
                    }
                }
                case SIZE_IMAGES -> images(sb, urls(images, ImageSlot.SIZE));
            }
        }
        return sb.append("</div>").toString();
    }

    private static void images(StringBuilder sb, List<String> urls) {
        for (String u : urls) {
            sb.append("<img src=\"").append(HtmlUtils.htmlEscape(u)).append("\" style=\"max-width:100%;display:block;margin:0 auto 12px\">");
        }
    }

    private static String sizeTable(Product p, StyleProfile style) {
        Set<String> parts = new LinkedHashSet<>();
        p.getMeasurements().forEach(m -> parts.addAll(m.getMeasures().keySet()));
        StringBuilder t = new StringBuilder("<table style=\"margin:24px auto;border-collapse:collapse;font-size:13px\"><tr><th style=\"border:1px solid #ddd;padding:6px 10px\">사이즈(cm)</th>");
        parts.forEach(pt -> t.append("<th style=\"border:1px solid #ddd;padding:6px 10px\">").append(HtmlUtils.htmlEscape(pt)).append("</th>"));
        t.append("</tr>");
        for (ProductMeasurement m : p.getMeasurements()) {
            t.append("<tr><td style=\"border:1px solid #ddd;padding:6px 10px\">").append(HtmlUtils.htmlEscape(style.size(m.getSize()))).append("</td>");
            for (String pt : parts) {
                BigDecimal v = m.getMeasures().get(pt);
                t.append("<td style=\"border:1px solid #ddd;padding:6px 10px\">").append(v == null ? "-" : v.stripTrailingZeros().toPlainString()).append("</td>");
            }
            t.append("</tr>");
        }
        return t.append("</table>").toString();
    }

    private static List<String> urls(List<UploadedImage> images, ImageSlot slot) {
        return images.stream().filter(i -> i.image().getSlot() == slot).map(UploadedImage::channelUrl).toList();
    }

    private static String displayColor(ProductOption o) {
        return blank(o.getColorDisplay()) ? o.getColor() : o.getColorDisplay();
    }

    private static String digits(String phone) {
        return phone == null ? "" : phone.replaceAll("[^0-9]", "");
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    /** 기존 상품에서 판매자 공통 값만 골라 settings 로 만든다 */
    public static Map<String, Object> templateSettings(Map<String, Object> originProductResponse) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (!(originProductResponse.get("originProduct") instanceof Map<?, ?> origin)) {
            throw ChannelException.invalid("원상품 응답 형식이 예상과 다릅니다");
        }
        Object delivery = origin.get("deliveryInfo");
        if (delivery != null) {
            out.put("deliveryInfo", delivery);
        }
        if (origin.get("detailAttribute") instanceof Map<?, ?> d) {
            for (String k : List.of("originAreaInfo", "taxType", "minorPurchasable", "certificationTargetExcludeContent")) {
                if (d.get(k) != null) {
                    out.put(k, d.get(k));
                }
            }
            if (d.get("naverShoppingSearchInfo") instanceof Map<?, ?> s) {
                Map<String, Object> keep = new LinkedHashMap<>();
                for (String k : List.of("brandName", "manufacturerName")) {
                    if (s.get(k) != null) {
                        keep.put(k, s.get(k));
                    }
                }
                out.put("naverShoppingSearchInfo", keep);
            }
        }
        if (originProductResponse.get("smartstoreChannelProduct") instanceof Map<?, ?> ch && ch.get("naverShoppingRegistration") != null) {
            out.put("naverShoppingRegistration", ch.get("naverShoppingRegistration"));
        }
        return out;
    }
}
