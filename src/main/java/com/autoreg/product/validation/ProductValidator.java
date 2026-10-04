package com.autoreg.product.validation;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

import com.autoreg.product.ImageSlot;
import com.autoreg.product.NoticeField;
import com.autoreg.product.Product;
import com.autoreg.product.ProductMeasurement;
import com.autoreg.product.ProductOption;

/**
 * 등록 전 필수값 검증. 어차피 등록 못 할 상품에 LLM 토큰을 쓰지 않도록 생성 단계보다 먼저 돈다.
 */
@Component
public class ProductValidator {

    public static final int NAME_MAX = 100;
    public static final int KEYWORDS_MAX = 10;

    public List<ValidationIssue> validate(Product p, ValidationPhase phase) {
        List<ValidationIssue> issues = new ArrayList<>();
        checkBasics(p, issues);
        checkOptions(p, issues);
        checkMeasurements(p, issues);
        checkNotice(p, issues);
        checkImages(p, issues);
        if (phase == ValidationPhase.READY) {
            checkTexts(p, issues);
        }
        return issues;
    }

    private static void checkBasics(Product p, List<ValidationIssue> issues) {
        if (blank(p.getCategory())) {
            issues.add(new ValidationIssue("category", "REQUIRED", "카테고리를 선택하세요"));
        }
        if (p.getSalePrice() == null || p.getSalePrice() <= 0) {
            issues.add(new ValidationIssue("salePrice", "REQUIRED", "판매가를 입력하세요"));
        }
    }

    private static void checkOptions(Product p, List<ValidationIssue> issues) {
        List<ProductOption> options = p.getOptions();
        if (options.isEmpty()) {
            issues.add(new ValidationIssue("options", "REQUIRED", "옵션(색상×사이즈)을 1개 이상 입력하세요"));
            return;
        }
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < options.size(); i++) {
            ProductOption o = options.get(i);
            if (blank(o.getColor())) {
                issues.add(new ValidationIssue("options[" + i + "].color", "REQUIRED", "옵션 " + (i + 1) + "의 색상이 비어 있습니다"));
            }
            if (blank(o.getSize())) {
                issues.add(new ValidationIssue("options[" + i + "].size", "REQUIRED", "옵션 " + (i + 1) + "의 사이즈가 비어 있습니다"));
            }
            if (o.getStock() < 0) {
                issues.add(new ValidationIssue("options[" + i + "].stock", "INVALID", "재고는 0 이상이어야 합니다"));
            }
            if (!seen.add(o.getColor() + "\u0000" + o.getSize())) {
                issues.add(new ValidationIssue("options[" + i + "]", "DUPLICATE",
                        "중복 옵션: " + o.getColor() + " / " + o.getSize()));
            }
        }
        if (options.stream().allMatch(o -> o.getStock() == 0)) {
            issues.add(new ValidationIssue("options", "NO_STOCK", "모든 옵션의 재고가 0입니다"));
        }
    }

    /** 옵션에 있는 사이즈마다 실측이 하나 이상 있어야 한다 */
    private static void checkMeasurements(Product p, List<ValidationIssue> issues) {
        Set<String> sizes = p.getOptions().stream().map(ProductOption::getSize).filter(s -> !blank(s))
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Map<String, ProductMeasurement> bySize = p.getMeasurements().stream()
                .collect(Collectors.toMap(ProductMeasurement::getSize, m -> m, (a, b) -> a));
        for (String size : sizes) {
            ProductMeasurement m = bySize.get(size);
            boolean hasValue = m != null && m.getMeasures().values().stream()
                    .anyMatch(v -> v != null && v.compareTo(BigDecimal.ZERO) > 0);
            if (!hasValue) {
                issues.add(new ValidationIssue("measurements." + size, "REQUIRED", size + " 사이즈 실측을 입력하세요"));
            }
        }
        p.getMeasurements().forEach(m -> m.getMeasures().forEach((part, v) -> {
            if (v != null && v.compareTo(BigDecimal.ZERO) < 0) {
                issues.add(new ValidationIssue("measurements." + m.getSize(), "INVALID",
                        m.getSize() + " " + part + " 실측값이 음수입니다"));
            }
        }));
    }

    private static void checkNotice(Product p, List<ValidationIssue> issues) {
        for (NoticeField f : NoticeField.values()) {
            if (f.required() && blank(f.get(p))) {
                issues.add(new ValidationIssue("notice." + f.key(), "REQUIRED", "고시정보 '" + f.label() + "'을(를) 입력하세요"));
            }
        }
    }

    private static void checkImages(Product p, List<ValidationIssue> issues) {
        for (ImageSlot slot : ImageSlot.values()) {
            long count = p.getImages().stream().filter(i -> i.getSlot() == slot).count();
            if (count < slot.minCount()) {
                issues.add(new ValidationIssue("images." + slot.value(), "IMAGE_SLOT_SHORT",
                        slot.label() + " 이미지가 " + slot.minCount() + "장 이상 필요합니다 (현재 " + count + "장)"));
            }
        }
    }

    private static void checkTexts(Product p, List<ValidationIssue> issues) {
        if (blank(p.getName())) {
            issues.add(new ValidationIssue("name", "REQUIRED", "상품명을 입력하거나 생성하세요"));
        } else if (p.getName().length() > NAME_MAX) {
            issues.add(new ValidationIssue("name", "TOO_LONG", "상품명은 " + NAME_MAX + "자 이하여야 합니다"));
        }
        if (blank(p.getDescription())) {
            issues.add(new ValidationIssue("description", "REQUIRED", "상세설명을 입력하거나 생성하세요"));
        }
        if (p.getSearchKeywords().size() > KEYWORDS_MAX) {
            issues.add(new ValidationIssue("searchKeywords", "TOO_MANY", "검색키워드는 " + KEYWORDS_MAX + "개 이하여야 합니다"));
        }
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
