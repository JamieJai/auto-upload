package com.autoreg.product;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/** 색상 목록 × 사이즈 목록을 옵션 행으로 펼친다. 색상 순서가 먼저다. */
public final class OptionCombiner {

    private OptionCombiner() {}

    public static List<ProductOption> combine(String productCode, List<String> colors, List<String> sizes, int stock) {
        List<String> cs = clean(colors);
        List<String> ss = clean(sizes);
        if (cs.isEmpty() || ss.isEmpty()) {
            throw new IllegalArgumentException("색상과 사이즈를 각각 1개 이상 입력하세요");
        }
        if (stock < 0) {
            throw new IllegalArgumentException("재고는 0 이상이어야 합니다");
        }
        List<ProductOption> out = new ArrayList<>();
        int order = 0;
        for (int ci = 0; ci < cs.size(); ci++) {
            for (String size : ss) {
                ProductOption o = new ProductOption();
                o.setColor(cs.get(ci));
                o.setSize(size);
                o.setStock(stock);
                o.setSku(String.format("%s-%02d-%s", productCode, ci + 1, size).replaceAll("[^A-Za-z0-9-]", ""));
                o.setSortOrder(order++);
                out.add(o);
            }
        }
        return out;
    }

    private static List<String> clean(List<String> in) {
        if (in == null) {
            return List.of();
        }
        return new ArrayList<>(in.stream().filter(s -> s != null && !s.isBlank()).map(String::trim)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new)));
    }
}
