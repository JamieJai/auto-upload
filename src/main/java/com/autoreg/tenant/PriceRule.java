package com.autoreg.tenant;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 도매가 → 판매가. 판매가 = 올림((도매가 × multiplier + add) / roundUnit) × roundUnit − subtract.
 * 예: {multiplier 2, roundUnit 1000, subtract 100} 이면 18,000 → 35,900.
 * defaultStock: 확장으로 받은 상품의 옵션당 기본 재고.
 */
public final class PriceRule {

    private PriceRule() {}

    public static Integer salePrice(Integer wholesale, Map<String, Object> rule) {
        if (wholesale == null || wholesale <= 0 || rule == null) {
            return null;
        }
        double v = wholesale * num(rule, "multiplier", 1) + num(rule, "add", 0);
        double unit = num(rule, "roundUnit", 0);
        if (unit > 0) {
            v = Math.ceil(v / unit) * unit;
        }
        v -= num(rule, "subtract", 0);
        return v > 0 ? (int) Math.round(v) : null;
    }

    public static int defaultStock(Map<String, Object> rule) {
        return rule == null ? 10 : (int) num(rule, "defaultStock", 10);
    }

    /** 저장 전에 숫자로 정리한다. 모르는 키는 거부 */
    static Map<String, Object> check(Map<String, Object> in) {
        Map<String, Object> out = new LinkedHashMap<>();
        in.forEach((k, v) -> {
            if (!java.util.List.of("multiplier", "add", "roundUnit", "subtract", "defaultStock").contains(k)) {
                throw new IllegalArgumentException("알 수 없는 가격 규칙 항목: " + k);
            }
            if (v == null || String.valueOf(v).isBlank()) {
                return;
            }
            try {
                double d = Double.parseDouble(String.valueOf(v));
                if (d < 0 || (k.equals("multiplier") && d == 0)) {
                    throw new NumberFormatException();
                }
                out.put(k, d);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("가격 규칙 " + k + " 값이 올바르지 않습니다: " + v);
            }
        });
        return out;
    }

    private static double num(Map<String, Object> m, String k, double def) {
        Object v = m.get(k);
        if (v == null) {
            return def;
        }
        try {
            return Double.parseDouble(String.valueOf(v));
        } catch (NumberFormatException e) {
            return def;
        }
    }
}
