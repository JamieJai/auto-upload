package com.autoreg.intake;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import com.autoreg.llm.LlmClient;

import lombok.RequiredArgsConstructor;
import tools.jackson.databind.JsonNode;

/**
 * 도매처 원문에서 사실값만 뽑는다. 생성이 아니라 추출이라 AI 생성 금지 항목(소재·실측 등)도 다루되,
 * 뽑은 숫자가 원문에 실제로 있는지 대조해 없으면 버리고 메모를 남긴다.
 */
@Component
@RequiredArgsConstructor
public class SourceExtractor {

    static final String SYSTEM = """
            너는 동대문 도매처 상품 페이지 글에서 상품 정보를 옮겨 적는 사람이다. 규칙:
            1. 글에 적힌 값만 옮긴다. 추측·계산·보완하지 않는다. 없으면 null 또는 빈 배열.
            2. 숫자는 글에 적힌 그대로 쓴다 (단위 cm, 원 은 빼고 숫자만).
            3. 가격이 여러 개면 도매가(공급가)를 고른다. 소비자가·할인 전 가격은 고르지 않는다.
            4. 실측은 사이즈별로 부위(총장, 어깨너비, 가슴단면 등)와 cm 값을 옮긴다. 단면/둘레 표기는 글 그대로 둔다.
            5. 카테고리는 주어진 목록 중 하나만 고르고, 맞는 게 없으면 null.
            6. nameHint 는 페이지에 적힌 상품명을 글자 그대로 옮긴다 (괄호·영문 약어 포함, 고치지 않는다).
            7. englishWords 에는 nameHint 에 나오는 영어 단어·약어(알파벳 덩어리)마다, 한국 여성의류 쇼핑몰에서 쓰는
               한글 표기를 적는다. 예: mtm→맨투맨, ops→원피스, knit→니트, cardigan→가디건, v→브이, pk→피케.
               ko 는 한글로만 쓴다.
            JSON 으로만 답한다.""";

    static final String SCHEMA = """
            {"type":"object","additionalProperties":false,
             "required":["wholesalePrice","colors","sizes","material","originCountry","washCare","category","measurements","nameHint","englishWords"],
             "properties":{
              "wholesalePrice":{"type":["integer","null"]},
              "colors":{"type":"array","items":{"type":"string"}},
              "sizes":{"type":"array","items":{"type":"string"}},
              "material":{"type":["string","null"]},
              "originCountry":{"type":["string","null"]},
              "washCare":{"type":["string","null"]},
              "category":{"type":["string","null"]},
              "nameHint":{"type":["string","null"]},
              "englishWords":{"type":"array","items":{"type":"object","additionalProperties":false,"required":["en","ko"],
                "properties":{"en":{"type":"string"},"ko":{"type":"string"}}}},
              "measurements":{"type":"array","items":{"type":"object","additionalProperties":false,"required":["size","parts"],
                "properties":{"size":{"type":"string"},"parts":{"type":"array","items":{"type":"object","additionalProperties":false,
                  "required":["part","cm"],"properties":{"part":{"type":"string"},"cm":{"type":"number"}}}}}}}
             }}""";

    private final LlmClient llm;

    /** 원문과 대조를 마친 결과 */
    public record Extracted(Integer wholesalePrice, List<String> colors, List<String> sizes, String material,
            String originCountry, String washCare, String category, String nameHint, Map<String, String> englishWords,
            Map<String, Map<String, BigDecimal>> measurements, List<String> dropped, JsonNode raw) {}

    public Extracted extract(String title, String text, List<String> categories) {
        String prompt = "## 카테고리 목록\n" + (categories.isEmpty() ? "(없음)" : String.join(", ", categories))
                + "\n\n## 페이지 제목\n" + (title == null ? "" : title) + "\n\n## 페이지 글\n" + text;
        return verify(llm.generate(SYSTEM, prompt, SCHEMA), (title == null ? "" : title) + "\n" + text, categories);
    }

    static Extracted verify(JsonNode out, String source, List<String> categories) {
        Set<String> numbers = numbersIn(source);
        List<String> dropped = new ArrayList<>();

        Integer price = null;
        if (out.path("wholesalePrice").isNumber()) {
            int p = out.path("wholesalePrice").asInt();
            if (numbers.contains(String.valueOf(p))) {
                price = p;
            } else {
                dropped.add("도매가 " + p + " (원문에 없음)");
            }
        }
        String material = text(out, "material");
        if (material != null) {
            // 혼용률 숫자가 모두 원문에 있어야 한다
            for (String n : numbersIn(material)) {
                if (!numbers.contains(n)) {
                    dropped.add("소재 '" + material + "' (" + n + " 이 원문에 없음)");
                    material = null;
                    break;
                }
            }
        }
        Map<String, Map<String, BigDecimal>> measures = new LinkedHashMap<>();
        for (JsonNode m : out.path("measurements")) {
            String size = m.path("size").asString("").strip();
            if (size.isEmpty()) {
                continue;
            }
            Map<String, BigDecimal> parts = new LinkedHashMap<>();
            for (JsonNode pt : m.path("parts")) {
                String part = pt.path("part").asString("").strip();
                if (part.isEmpty() || !pt.path("cm").isNumber()) {
                    continue;
                }
                BigDecimal cm = pt.path("cm").decimalValue().stripTrailingZeros();
                if (numbers.contains(cm.toPlainString())) {
                    parts.put(part, cm);
                } else {
                    dropped.add("실측 " + size + " " + part + " " + cm.toPlainString() + " (원문에 없음)");
                }
            }
            if (!parts.isEmpty()) {
                measures.put(size, parts);
            }
        }
        String category = text(out, "category");
        if (category != null && !categories.contains(category)) {
            category = null;
        }
        String nameHint = text(out, "nameHint");
        // 상품명에 실제로 있는 영어 단어만, 한글로 된 번역만 받는다
        Map<String, String> english = new LinkedHashMap<>();
        String lowerHint = nameHint == null ? "" : nameHint.toLowerCase(java.util.Locale.ROOT);
        for (JsonNode w : out.path("englishWords")) {
            String en = w.path("en").asString("").strip();
            String ko = w.path("ko").asString("").strip();
            if (en.matches("[A-Za-z]+") && lowerHint.contains(en.toLowerCase(java.util.Locale.ROOT)) && ko.matches("[가-힣 ]+")) {
                english.put(en, ko);
            }
        }
        return new Extracted(price, list(out, "colors"), list(out, "sizes"), material, text(out, "originCountry"),
                text(out, "washCare"), category, nameHint, english, measures, dropped, out);
    }

    /** 원문에 나오는 숫자들 ("48.5", "18,000" → "18000", "48.50" → "48.5") */
    static Set<String> numbersIn(String s) {
        Set<String> out = new LinkedHashSet<>();
        Matcher m = Pattern.compile("\\d[\\d,]*(?:\\.\\d+)?").matcher(s == null ? "" : s);
        while (m.find()) {
            String n = m.group().replace(",", "");
            try {
                out.add(new BigDecimal(n).stripTrailingZeros().toPlainString());
            } catch (NumberFormatException ignored) {
                // 숫자가 아니면 건너뛴다
            }
        }
        return out;
    }

    private static String text(JsonNode out, String f) {
        JsonNode n = out.path(f);
        if (n.isNull() || n.isMissingNode()) {
            return null;
        }
        String s = n.asString("").strip();
        return s.isEmpty() ? null : s;
    }

    private static List<String> list(JsonNode out, String f) {
        LinkedHashSet<String> s = new LinkedHashSet<>();
        out.path(f).forEach(n -> {
            String v = n.asString("").strip();
            if (!v.isEmpty() && v.length() <= 30) {
                s.add(v);
            }
        });
        return new ArrayList<>(s);
    }
}
