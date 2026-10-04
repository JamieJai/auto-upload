package com.autoreg.excel;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import com.autoreg.product.ProductDtos.ProductRequest;
import com.autoreg.product.validation.ValidationIssue;

public final class ExcelDtos {

    private ExcelDtos() {}

    /** 파싱된 행 1개. errors 가 있으면 등록하지 않는다. warnings 는 등록 후 보완할 항목이다 */
    public record ParsedRow(
            int rowNumber,
            ProductRequest product,
            List<String> colors,
            List<String> sizes,
            int stock,
            Map<String, Map<String, BigDecimal>> measurements,
            List<String> errors,
            List<ValidationIssue> warnings) {

        public boolean importable() {
            return errors.isEmpty();
        }
    }

    /** notes: 특정 행에 붙지 않는 안내 (예: 실측 시트의 모르는 상품코드) */
    public record PreviewResponse(int total, int importable, int blocked, List<String> notes, List<ParsedRow> rows) {}

    public record ImportResult(int created, int skipped, List<CreatedRow> rows) {
        public record CreatedRow(int rowNumber, String code, Long productId, String error) {}
    }
}
