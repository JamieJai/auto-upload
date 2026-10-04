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

    public static Map<String, Object> build(Product p, String categoryId, List<UploadedImage> images, Map<String, Object> settings) {
        List<String> missing = new ArrayList<>();
        for (String k : List.of("deliveryInfo", "originAreaInfo")) {
            if (!(settings.get(k) instanceof Map<?, ?>)) {
                missing.add(k);
            }
        }
        if (!missing.isEmpty()) {
            throw ChannelException.invalid("채널 계정 설정에 " + String.join(", ", missing)
                    + " 가 없습니다. 판매자 관리 → 채널 계정에서 기존 상품을 템플릿으로 가져오세요");
        }
        List<String> mains = urls(images, ImageSlot.MAIN);
        if (mains.isEmpty()) {
            throw ChannelException.invalid("대표 이미지가 없습니다");
        }
        List<String> optional = new ArrayList<>(mains.subList(1, mains.size()));
        optional.addAll(urls(images, ImageSlot.SUB));

        Map<String, Object> origin = new LinkedHashMap<>();
        origin.put("statusType", "SALE");
        origin.put("saleType", "NEW");
        origin.put("leafCategoryId", categoryId);
        origin.put("name", p.getName());
        origin.put("detailContent", detailHtml(p, images));
        origin.put("images", Map.of(
                "representativeImage", Map.of("url", mains.get(0)),
                "optionalImages", optional.stream().limit(MAX_OPTIONAL_IMAGES).map(u -> Map.of("url", u)).toList()));
        origin.put("salePrice", p.getSalePrice());
        origin.put("stockQuantity", p.getOptions().stream().mapToInt(ProductOption::getStock).sum());
        origin.put("deliveryInfo", settings.get("deliveryInfo"));
        origin.put("detailAttribute", detailAttribute(p, settings));

        Map<String, Object> channel = new LinkedHashMap<>();
        channel.put("channelProductName", p.getName());
        channel.put("naverShoppingRegistration", settings.getOrDefault("naverShoppingRegistration", true));
        channel.put("channelProductDisplayStatusType", settings.getOrDefault("displayStatus", "SUSPENSION"));
        channel.put("storeKeepExclusiveProduct", false);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("originProduct", origin);
        body.put("smartstoreChannelProduct", channel);
        return body;
    }

    private static Map<String, Object> detailAttribute(Product p, Map<String, Object> s) {
        Map<String, Object> d = new LinkedHashMap<>();
        Map<String, Object> search = new LinkedHashMap<>();
        if (s.get("naverShoppingSearchInfo") instanceof Map<?, ?> m) {
            m.forEach((k, v) -> {
                if (!"modelId".equals(k) && !"modelName".equals(k)) {
                    search.put(String.valueOf(k), v);
                }
            });
        }
        search.putIfAbsent("manufacturerName", p.getManufacturer());
        search.put("catalogMatchingYn", false);
        d.put("naverShoppingSearchInfo", search);
        d.put("afterServiceInfo", Map.of(
                "afterServiceTelephoneNumber", digits(p.getAsPhone()),
                "afterServiceGuideContent", p.getAsManager()));
        d.put("originAreaInfo", s.get("originAreaInfo"));
        d.put("sellerCodeInfo", Map.of("sellerManagementCode", p.getCode()));
        d.put("optionInfo", optionInfo(p));
        d.put("taxType", s.getOrDefault("taxType", "TAX"));
        d.put("minorPurchasable", s.getOrDefault("minorPurchasable", true));
        d.put("certificationTargetExcludeContent",
                s.getOrDefault("certificationTargetExcludeContent", Map.of("kcCertifiedProductExclusionYn", "TRUE")));
        d.put("productInfoProvidedNotice", Map.of("productInfoProvidedNoticeType", "WEAR", "wear", wearNotice(p)));
        List<Map<String, String>> tags = p.getSearchKeywords().stream().limit(MAX_TAGS).map(k -> Map.of("text", k)).toList();
        if (!tags.isEmpty()) {
            d.put("seoInfo", Map.of("sellerTags", tags));
        }
        return d;
    }

    private static Map<String, Object> optionInfo(Product p) {
        List<Map<String, Object>> combos = new ArrayList<>();
        for (ProductOption o : p.getOptions()) {
            Map<String, Object> c = new LinkedHashMap<>();
            c.put("optionName1", displayColor(o));
            c.put("optionName2", o.getSize());
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
        info.put("optionCombinationGroupNames", Map.of("optionGroupName1", "색상", "optionGroupName2", "사이즈"));
        info.put("optionCombinations", combos);
        info.put("useStockManagement", true);
        return info;
    }

    /** 상품정보제공고시 의류(WEAR). 값은 모두 입력값 그대로 (AI 생성 금지 항목) */
    private static Map<String, Object> wearNotice(Product p) {
        Set<String> colors = p.getOptions().stream().map(SmartStorePayload::displayColor).collect(Collectors.toCollection(LinkedHashSet::new));
        Set<String> sizes = p.getOptions().stream().map(ProductOption::getSize).collect(Collectors.toCollection(LinkedHashSet::new));
        Map<String, Object> w = new LinkedHashMap<>();
        w.put("material", p.getMaterial());
        w.put("color", String.join("/", colors));
        w.put("size", String.join("/", sizes));
        w.put("manufacturer", p.getManufacturer());
        w.put("caution", p.getWashCare());
        w.put("packDate", blank(p.getManufacturedYm()) ? "상세설명참조" : p.getManufacturedYm());
        w.put("warrantyPolicy", p.getQualityAssurance());
        w.put("afterServiceDirector", p.getAsManager() + " " + p.getAsPhone());
        return w;
    }

    /** 상세설명 HTML: 문구 → 디테일컷 → 실측표 → 사이즈표 이미지 */
    static String detailHtml(Product p, List<UploadedImage> images) {
        StringBuilder sb = new StringBuilder("<div style=\"max-width:860px;margin:0 auto;text-align:center;font-size:15px;line-height:1.8;color:#222\">");
        if (!blank(p.getDescription())) {
            for (String para : p.getDescription().strip().split("\\n\\s*\\n")) {
                sb.append("<p style=\"margin:0 0 18px\">").append(HtmlUtils.htmlEscape(para.strip()).replace("\n", "<br>")).append("</p>");
            }
        }
        for (String u : urls(images, ImageSlot.DETAIL)) {
            sb.append("<img src=\"").append(HtmlUtils.htmlEscape(u)).append("\" style=\"max-width:100%;display:block;margin:0 auto 12px\">");
        }
        if (!p.getMeasurements().isEmpty()) {
            sb.append(sizeTable(p));
        }
        for (String u : urls(images, ImageSlot.SIZE)) {
            sb.append("<img src=\"").append(HtmlUtils.htmlEscape(u)).append("\" style=\"max-width:100%;display:block;margin:12px auto\">");
        }
        return sb.append("</div>").toString();
    }

    private static String sizeTable(Product p) {
        Set<String> parts = new LinkedHashSet<>();
        p.getMeasurements().forEach(m -> parts.addAll(m.getMeasures().keySet()));
        StringBuilder t = new StringBuilder("<table style=\"margin:24px auto;border-collapse:collapse;font-size:13px\"><tr><th style=\"border:1px solid #ddd;padding:6px 10px\">사이즈(cm)</th>");
        parts.forEach(pt -> t.append("<th style=\"border:1px solid #ddd;padding:6px 10px\">").append(HtmlUtils.htmlEscape(pt)).append("</th>"));
        t.append("</tr>");
        for (ProductMeasurement m : p.getMeasurements()) {
            t.append("<tr><td style=\"border:1px solid #ddd;padding:6px 10px\">").append(HtmlUtils.htmlEscape(m.getSize())).append("</td>");
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
