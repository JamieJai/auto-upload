package com.autoreg.tenant;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 도매가 → 판매가 = 단위로 맞추기(도매가 × multiplier + add) − subtract.
 * roundMode: UP(올림, 기본) · DOWN(버림) · NEAREST(반올림). 예: 21,000 × 1.6, 100원 버림 → 33,600.
 * 계산은 BigDecimal 로 한다 (21000 × 1.6 이 33599.999… 가 되어 버림에서 33,500 이 되는 일을 막는다).
 * defaultStock: 확장으로 받은 상품의 옵션당 기본 재고.
 */
public final class PriceRule {

    static final List<String> KEYS = List.of("multiplier", "add", "roundUnit", "roundMode", "subtract", "defaultStock");

    private PriceRule() {}

    public static Integer salePrice(Integer wholesale, Map<String, Object> rule) {
        if (wholesale == null || wholesale <= 0 || rule == null) {
            return null;
        }
        BigDecimal v = BigDecimal.valueOf(wholesale).multiply(num(rule, "multiplier", "1")).add(num(rule, "add", "0"));
        BigDecimal unit = num(rule, "roundUnit", "0");
        if (unit.signum() > 0) {
            RoundingMode mode = switch (String.valueOf(rule.getOrDefault("roundMode", "UP"))) {
                case "DOWN" -> RoundingMode.DOWN;
                case "NEAREST" -> RoundingMode.HALF_UP;
                default -> RoundingMode.UP;
            };
            v = v.divide(unit, 0, mode).multiply(unit);
        }
        v = v.subtract(num(rule, "subtract", "0"));
        return v.signum() > 0 ? v.setScale(0, RoundingMode.HALF_UP).intValueExact() : null;
    }

    public static int defaultStock(Map<String, Object> rule) {
        return rule == null ? 10 : num(rule, "defaultStock", "10").intValue();
    }

    /** 저장 전에 정리한다. 모르는 키는 거부 */
    static Map<String, Object> check(Map<String, Object> in) {
        Map<String, Object> out = new LinkedHashMap<>();
        in.forEach((k, v) -> {
            if (!KEYS.contains(k)) {
                throw new IllegalArgumentException("알 수 없는 가격 규칙 항목: " + k);
            }
            if (v == null || String.valueOf(v).isBlank()) {
                return;
            }
            if (k.equals("roundMode")) {
                if (!List.of("UP", "DOWN", "NEAREST").contains(String.valueOf(v))) {
                    throw new IllegalArgumentException("단위 맞추기는 UP(올림)·DOWN(버림)·NEAREST(반올림) 중 하나입니다");
                }
                out.put(k, String.valueOf(v));
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

    private static BigDecimal num(Map<String, Object> m, String k, String def) {
        Object v = m.get(k);
        try {
            return new BigDecimal(v == null ? def : String.valueOf(v));
        } catch (NumberFormatException e) {
            return new BigDecimal(def);
        }
    }
}
