package com.autoreg.workflow;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import com.autoreg.channel.CategoryMapping;
import com.autoreg.channel.CategoryMappingRepository;
import com.autoreg.intake.ProductSource;
import com.autoreg.intake.ProductSourceRepository;
import com.autoreg.intake.SourceExtractor;
import com.autoreg.job.Job;
import com.autoreg.job.JobException;
import com.autoreg.job.JobService;
import com.autoreg.llm.LlmException;
import com.autoreg.product.Product;
import com.autoreg.product.ProductDtos.CombineRequest;
import com.autoreg.product.ProductDtos.MeasurementRequest;
import com.autoreg.product.ProductRepository;
import com.autoreg.product.ProductService;
import com.autoreg.product.ProductStatus;
import com.autoreg.tenant.PriceRule;
import com.autoreg.tenant.Tenant;
import com.autoreg.tenant.TenantService;

import lombok.RequiredArgsConstructor;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/** 원문에서 뽑은 값으로 비어 있는 칸만 채운다. 사람이 이미 넣은 값은 건드리지 않는다 */
@Component
@RequiredArgsConstructor
public class ExtractJobHandler {

    private final ProductRepository products;
    private final ProductSourceRepository sources;
    private final CategoryMappingRepository mappings;
    private final TenantService tenants;
    private final ProductService productService;
    private final SourceExtractor extractor;
    private final JobService jobs;
    private final TransactionTemplate tx;
    private final JsonMapper json;

    public void handle(Job job) {
        jobs.step(job.getId(), "EXTRACT", "도매처 원문에서 값 추출");
        String done = tx.execute(s -> {
            Product p = products.findByIdAndTenantId(job.getProductId(), job.getTenantId())
                    .orElseThrow(() -> JobException.invalid("상품이 없습니다"));
            if (!EnumSet.of(ProductStatus.DRAFT, ProductStatus.NEEDS_INPUT).contains(p.getStatus())) {
                return "상품 상태가 " + p.getStatus() + " 라 건너뜀";
            }
            ProductSource src = sources.findById(p.getId()).orElseThrow(() -> JobException.invalid("원문이 없습니다"));
            Tenant t = tenants.get(job.getTenantId());
            List<String> categories = mappings.findByTenantIdOrderByChannelAscCategoryAsc(t.getId()).stream()
                    .map(CategoryMapping::getCategory).distinct().toList();
            SourceExtractor.Extracted ex;
            try {
                ex = extractor.extract(src.getTitle(), src.getRawText(), categories);
            } catch (LlmException e) {
                throw e.retryable() ? JobException.retryable(e.getMessage(), e.retryAfter()) : JobException.invalid(e.getMessage());
            }
            List<String> filled = new ArrayList<>();
            if (p.getCategory() == null && ex.category() != null) {
                p.setCategory(ex.category());
                filled.add("카테고리");
            }
            if (p.getSalePrice() == null) {
                Integer price = PriceRule.salePrice(ex.wholesalePrice(), t.getPriceRule());
                if (price != null) {
                    p.setSalePrice(price);
                    filled.add("판매가(도매가 " + ex.wholesalePrice() + "원 → " + price + "원)");
                }
            }
            if (p.getMaterial() == null && ex.material() != null) {
                p.setMaterial(ex.material());
                filled.add("소재");
            }
            if ((p.getOriginCountry() == null || p.getOriginCountry().isBlank()) && ex.originCountry() != null) {
                p.setOriginCountry(ex.originCountry());
                filled.add("제조국");
            }
            if ((p.getWashCare() == null || p.getWashCare().isBlank()) && ex.washCare() != null) {
                p.setWashCare(ex.washCare());
                filled.add("세탁방법");
            }
            List<String> sizes = ex.sizes();
            String lower = src.getRawText().toLowerCase(Locale.ROOT);
            if (sizes.isEmpty() && (lower.contains("free") || lower.contains("프리"))) {
                sizes = List.of("FREE");
            }
            if (p.getOptions().isEmpty() && !ex.colors().isEmpty() && !sizes.isEmpty()) {
                productService.combineOptions(t.getId(), p.getId(),
                        new CombineRequest(ex.colors(), sizes, PriceRule.defaultStock(t.getPriceRule())));
                filled.add("옵션 " + ex.colors().size() + "색×" + sizes.size() + "사이즈");
            }
            if (p.getMeasurements().isEmpty() && !ex.measurements().isEmpty()) {
                productService.replaceMeasurements(t.getId(), p.getId(), ex.measurements().entrySet().stream()
                        .map(e -> new MeasurementRequest(e.getKey(), e.getValue())).toList());
                filled.add("실측 " + ex.measurements().size() + "사이즈");
            }
            src.setExtracted(json.convertValue(ex.raw(), new TypeReference<Map<String, Object>>() {}));
            src.setExtractedAt(OffsetDateTime.now());
            String note = "도매처 원문에서 " + (filled.isEmpty() ? "채운 값 없음" : String.join(", ", filled) + " 을(를) 채웠습니다")
                    + ". 원문과 대조한 뒤 제출하세요."
                    + (ex.dropped().isEmpty() ? "" : " 원문에서 확인되지 않아 버린 값: " + String.join("; ", ex.dropped()));
            src.setNotes(note);
            p.setReviewNote(note);
            return note;
        });
        jobs.succeed(job.getId(), done);
    }
}
