package com.autoreg.excel;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.autoreg.excel.ExcelDtos.ImportResult;
import com.autoreg.excel.ExcelDtos.ParsedRow;
import com.autoreg.excel.ExcelDtos.PreviewResponse;
import com.autoreg.excel.ExcelParser.RawRow;
import com.autoreg.product.OptionCombiner;
import com.autoreg.product.Product;
import com.autoreg.product.ProductDtos.CombineRequest;
import com.autoreg.product.ProductDtos.MeasurementRequest;
import com.autoreg.product.ProductMeasurement;
import com.autoreg.product.ProductRepository;
import com.autoreg.product.ProductService;
import com.autoreg.product.validation.ProductValidator;
import com.autoreg.product.validation.ValidationIssue;
import com.autoreg.product.validation.ValidationPhase;
import com.autoreg.tenant.Tenant;
import com.autoreg.tenant.TenantService;

import lombok.RequiredArgsConstructor;

/**
 * 엑셀 일괄 등록. 미리보기와 등록은 같은 파일을 두 번 받는다 (서버에 임시 상태를 두지 않는다).
 * 등록은 행마다 별도 트랜잭션이라 한 행이 실패해도 나머지는 들어간다.
 */
@Service
@RequiredArgsConstructor
public class ExcelImportService {

    private final TenantService tenants;
    private final ProductRepository products;
    private final ProductService productService;
    private final ProductValidator validator;
    private final TransactionTemplate tx;

    public PreviewResponse preview(Long tenantId, byte[] file) {
        Tenant tenant = tenants.get(tenantId);
        ExcelParser.Parsed parsed = ExcelParser.parse(file);
        List<ParsedRow> rows = parsed.rows().stream().map(r -> check(tenant, r)).toList();
        int ok = (int) rows.stream().filter(ParsedRow::importable).count();
        return new PreviewResponse(rows.size(), ok, rows.size() - ok, parsed.notes(), rows);
    }

    public ImportResult importRows(Long tenantId, byte[] file) {
        Tenant tenant = tenants.get(tenantId);
        List<ImportResult.CreatedRow> out = new ArrayList<>();
        int created = 0;
        for (RawRow raw : ExcelParser.parse(file).rows()) {
            ParsedRow row = check(tenant, raw);
            if (!row.importable()) {
                out.add(new ImportResult.CreatedRow(row.rowNumber(), raw.product().code(), null, String.join("; ", row.errors())));
                continue;
            }
            try {
                Long id = tx.execute(status -> create(tenantId, raw));
                out.add(new ImportResult.CreatedRow(row.rowNumber(), raw.product().code(), id, null));
                created++;
            } catch (RuntimeException e) {
                out.add(new ImportResult.CreatedRow(row.rowNumber(), raw.product().code(), null, e.getMessage()));
            }
        }
        return new ImportResult(created, out.size() - created, out);
    }

    private Long create(Long tenantId, RawRow raw) {
        Long id = productService.create(tenantId, raw.product()).id();
        if (!raw.colors().isEmpty() && !raw.sizes().isEmpty()) {
            productService.combineOptions(tenantId, id, new CombineRequest(raw.colors(), raw.sizes(), raw.stock()));
        }
        if (!raw.measurements().isEmpty()) {
            productService.replaceMeasurements(tenantId, id, raw.measurements().entrySet().stream()
                    .map(e -> new MeasurementRequest(e.getKey(), e.getValue())).toList());
        }
        return id;
    }

    /** 형식 오류 + DB 중복은 errors (등록 안 함), 상품 검증 결과는 warnings (등록 후 보완) */
    private ParsedRow check(Tenant tenant, RawRow raw) {
        List<String> errors = new ArrayList<>(raw.errors());
        String code = raw.product().code();
        if (code != null && !code.isEmpty() && products.existsByTenantIdAndCode(tenant.getId(), code)) {
            errors.add("이미 등록된 상품코드입니다: " + code);
        }
        List<ValidationIssue> warnings = List.of();
        if (errors.isEmpty()) {
            Product p = ProductService.transientProduct(tenant, raw.product());
            if (!raw.colors().isEmpty() && !raw.sizes().isEmpty()) {
                p.replaceOptions(OptionCombiner.combine(code, raw.colors(), raw.sizes(), raw.stock()));
            }
            raw.measurements().forEach((size, measures) -> p.getMeasurements().add(measurement(size, measures)));
            // 이미지는 엑셀로 올리지 않으므로 여기서는 보지 않는다
            warnings = validator.validate(p, ValidationPhase.INPUT, tenant.styleProfile()).stream()
                    .filter(i -> !i.field().startsWith("images.")).toList();
        }
        return new ParsedRow(raw.rowNumber(), raw.product(), raw.colors(), raw.sizes(), raw.stock(),
                raw.measurements(), errors, warnings);
    }

    private static ProductMeasurement measurement(String size, Map<String, BigDecimal> measures) {
        ProductMeasurement m = new ProductMeasurement();
        m.setSize(size);
        m.setMeasures(measures);
        return m;
    }
}
