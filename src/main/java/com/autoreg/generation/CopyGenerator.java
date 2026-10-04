package com.autoreg.generation;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

import com.autoreg.llm.LlmClient;
import com.autoreg.product.NoticeField;
import com.autoreg.product.Product;
import com.autoreg.product.ProductMeasurement;
import com.autoreg.product.ProductOption;
import com.autoreg.product.TextField;
import com.autoreg.product.TextField.Source;
import com.autoreg.product.validation.ProductValidator;
import com.autoreg.tenant.Tenant;

import lombok.RequiredArgsConstructor;
import tools.jackson.databind.JsonNode;

/**
 * 상품명·상세설명·검색키워드·옵션 표시명을 LLM 으로 만든다.
 * AI 생성 금지 항목(소재·원산지·제조사·실측·가격·재고·KC·세탁방법)은 응답 스키마에 없으므로 덮어쓸 수 없다.
 */
@Component
@RequiredArgsConstructor
public class CopyGenerator {

    static final String SYSTEM = """
            너는 20~30대 여성의류 온라인 쇼핑몰의 상품 문구 작성자다. 다음 규칙을 반드시 지킨다.
            1. 입력에 있는 사실만 쓴다. 소재·혼용률, 원산지, 제조사, 실측 치수, 가격, 재고, KC인증, 세탁방법은 새로 만들거나 바꾸지 않는다. 언급하려면 입력값을 그대로 옮긴다.
            2. 입력에 없는 기능·효과(예: 체형 보정, 냉감, 방수)를 지어내지 않는다.
            3. 최상급·과장 표현(최고, 1위, 완벽, 역대급)과 다른 브랜드 이름을 쓰지 않는다.
            4. 판매자 문체 기준이 있으면 따른다. 없으면 담백한 존댓말을 쓴다. 이모지는 기준에 허용이 있을 때만 쓴다.
            5. 요청된 필드만 JSON 으로 답한다.""";

    private final LlmClient llm;

    /** 비어 있는 문구 필드. 큐의 생성 단계가 이것만 채운다 */
    public static Set<TextField> emptyFields(Product p) {
        Set<TextField> out = EnumSet.noneOf(TextField.class);
        if (p.getName() == null || p.getName().isBlank()) {
            out.add(TextField.NAME);
        }
        if (p.getDescription() == null || p.getDescription().isBlank()) {
            out.add(TextField.DESCRIPTION);
        }
        if (p.getSearchKeywords().isEmpty()) {
            out.add(TextField.SEARCH_KEYWORDS);
        }
        return out;
    }

    /** fields 만 생성해 상품에 채우고 출처를 AI 로 표시한다. 호출자가 트랜잭션 안에서 저장한다 */
    public void generate(Product p, Tenant tenant, Set<TextField> fields) {
        if (fields.isEmpty()) {
            return;
        }
        if (fields.contains(TextField.OPTION_DISPLAY) && p.getOptions().isEmpty()) {
            throw new IllegalArgumentException("옵션이 없어 옵션 표시명을 만들 수 없습니다");
        }
        JsonNode out = llm.generate(SYSTEM, prompt(p, tenant, fields), schema(fields));
        apply(p, fields, out);
    }

    static String prompt(Product p, Tenant tenant, Set<TextField> fields) {
        StringBuilder sb = new StringBuilder();
        sb.append("## 판매자 문체 기준\n").append(blank(tenant.getBrandTone()) ? "(없음)" : tenant.getBrandTone()).append("\n\n");
        sb.append("## 상품 정보\n");
        line(sb, "카테고리", p.getCategory());
        line(sb, "판매가", p.getSalePrice() == null ? null : p.getSalePrice() + "원");
        for (NoticeField f : NoticeField.values()) {
            line(sb, f.label(), f.get(p));
        }
        Set<String> colors = p.getOptions().stream().map(ProductOption::getColor).collect(Collectors.toCollection(LinkedHashSet::new));
        Set<String> sizes = p.getOptions().stream().map(ProductOption::getSize).collect(Collectors.toCollection(LinkedHashSet::new));
        line(sb, "색상", String.join(", ", colors));
        line(sb, "사이즈", String.join(", ", sizes));
        for (ProductMeasurement m : p.getMeasurements()) {
            line(sb, "실측 " + m.getSize(), m.getMeasures().entrySet().stream()
                    .map(e -> e.getKey() + " " + plain(e.getValue()) + "cm").collect(Collectors.joining(", ")));
        }
        // 이미 사람이 쓴 문구는 참고용으로 준다 (생성 대상이 아닌 것만)
        if (!fields.contains(TextField.NAME)) {
            line(sb, "상품명", p.getName());
        }
        if (!fields.contains(TextField.DESCRIPTION)) {
            line(sb, "상세설명", p.getDescription());
        }
        sb.append("\n## 만들 것\n");
        if (fields.contains(TextField.NAME)) {
            sb.append("- name: 상품명. 20~50자, 최대 ").append(ProductValidator.NAME_MAX)
                    .append("자. 핵심 소재·핏·아이템명을 앞에. 색상·사이즈 나열과 특수문자 남발 금지\n");
        }
        if (fields.contains(TextField.DESCRIPTION)) {
            sb.append("- description: 상세설명. 300~800자 일반 텍스트(HTML·마크다운 금지). 착용감·연출·코디 위주, 문단은 줄바꿈으로\n");
        }
        if (fields.contains(TextField.SEARCH_KEYWORDS)) {
            sb.append("- searchKeywords: 검색키워드 5~").append(ProductValidator.KEYWORDS_MAX)
                    .append("개. 각 15자 이하, 띄어쓰기 없는 검색어 위주, 브랜드명 금지\n");
        }
        if (fields.contains(TextField.OPTION_DISPLAY)) {
            sb.append("- optionDisplays: 색상마다 {color: 입력 색상 그대로, display: 고객에게 보일 색상명 12자 이하}\n");
        }
        return sb.toString();
    }

    static String schema(Set<TextField> fields) {
        List<String> props = new ArrayList<>();
        List<String> required = new ArrayList<>();
        if (fields.contains(TextField.NAME)) {
            props.add("\"name\":{\"type\":\"string\"}");
            required.add("\"name\"");
        }
        if (fields.contains(TextField.DESCRIPTION)) {
            props.add("\"description\":{\"type\":\"string\"}");
            required.add("\"description\"");
        }
        if (fields.contains(TextField.SEARCH_KEYWORDS)) {
            props.add("\"searchKeywords\":{\"type\":\"array\",\"items\":{\"type\":\"string\"}}");
            required.add("\"searchKeywords\"");
        }
        if (fields.contains(TextField.OPTION_DISPLAY)) {
            props.add("\"optionDisplays\":{\"type\":\"array\",\"items\":{\"type\":\"object\",\"properties\":"
                    + "{\"color\":{\"type\":\"string\"},\"display\":{\"type\":\"string\"}},\"required\":[\"color\",\"display\"]}}");
            required.add("\"optionDisplays\"");
        }
        return "{\"type\":\"object\",\"properties\":{" + String.join(",", props) + "},\"required\":["
                + String.join(",", required) + "],\"additionalProperties\":false}";
    }

    /** 응답을 다듬어 채운다. 형식이 틀리면 GeneratedCopyException (재시도 대상) */
    static void apply(Product p, Set<TextField> fields, JsonNode out) {
        if (fields.contains(TextField.NAME)) {
            String name = oneLine(text(out, "name"));
            if (name.isEmpty() || name.length() > ProductValidator.NAME_MAX) {
                throw new GeneratedCopyException("생성된 상품명이 비었거나 " + ProductValidator.NAME_MAX + "자를 넘습니다");
            }
            p.setName(name);
            p.getFieldSources().put(TextField.NAME, Source.AI);
        }
        if (fields.contains(TextField.DESCRIPTION)) {
            String desc = text(out, "description").strip();
            if (desc.length() < 50) {
                throw new GeneratedCopyException("생성된 상세설명이 너무 짧습니다");
            }
            p.setDescription(desc.length() > 5000 ? desc.substring(0, 5000) : desc);
            p.getFieldSources().put(TextField.DESCRIPTION, Source.AI);
        }
        if (fields.contains(TextField.SEARCH_KEYWORDS)) {
            LinkedHashSet<String> kws = new LinkedHashSet<>();
            out.path("searchKeywords").forEach(n -> {
                String k = oneLine(n.asString(""));
                if (!k.isEmpty() && k.length() <= 30) {
                    kws.add(k);
                }
            });
            if (kws.isEmpty()) {
                throw new GeneratedCopyException("검색키워드가 생성되지 않았습니다");
            }
            p.setSearchKeywords(new ArrayList<>(kws).subList(0, Math.min(kws.size(), ProductValidator.KEYWORDS_MAX)));
            p.getFieldSources().put(TextField.SEARCH_KEYWORDS, Source.AI);
        }
        if (fields.contains(TextField.OPTION_DISPLAY)) {
            Map<String, String> byColor = new LinkedHashMap<>();
            out.path("optionDisplays").forEach(n -> {
                String display = oneLine(n.path("display").asString(""));
                if (!display.isEmpty() && display.length() <= 30) {
                    byColor.put(n.path("color").asString("").trim(), display);
                }
            });
            // 실제 색상 값은 건드리지 않고 표시명만 채운다. 모르는 색상 키는 버린다
            p.getOptions().forEach(o -> {
                String d = byColor.get(o.getColor());
                if (d != null) {
                    o.setColorDisplay(d);
                }
            });
            p.getFieldSources().put(TextField.OPTION_DISPLAY, Source.AI);
        }
    }

    private static String text(JsonNode out, String field) {
        return out.path(field).asString("");
    }

    private static String oneLine(String s) {
        return s == null ? "" : s.replaceAll("\\s+", " ").strip();
    }

    private static void line(StringBuilder sb, String label, String value) {
        if (!blank(value)) {
            sb.append("- ").append(label).append(": ").append(value.strip()).append('\n');
        }
    }

    private static String plain(BigDecimal v) {
        return v.stripTrailingZeros().toPlainString();
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
