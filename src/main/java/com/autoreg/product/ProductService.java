package com.autoreg.product;

import java.math.BigDecimal;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.autoreg.common.ConflictException;
import com.autoreg.common.NotFoundException;
import com.autoreg.product.ProductDtos.CombineRequest;
import com.autoreg.product.ProductDtos.MeasurementRequest;
import com.autoreg.product.ProductDtos.OptionRequest;
import com.autoreg.product.ProductDtos.ProductRequest;
import com.autoreg.product.ProductDtos.ProductResponse;
import com.autoreg.product.TextField.Source;
import com.autoreg.product.validation.ProductValidator;
import com.autoreg.product.validation.ValidationIssue;
import com.autoreg.product.validation.ValidationPhase;
import com.autoreg.tenant.Tenant;
import com.autoreg.tenant.TenantService;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ProductService {

    private final ProductRepository products;
    private final TenantService tenants;
    private final ProductValidator validator;

    public Page<Product> list(Long tenantId, ProductStatus status, Pageable pageable) {
        tenants.get(tenantId);
        return status == null ? products.findByTenantId(tenantId, pageable)
                : products.findByTenantIdAndStatus(tenantId, status, pageable);
    }

    /** 응답 DTO 는 트랜잭션 안에서 만든다 (open-in-view 꺼짐, 컬렉션은 지연 로딩) */
    public ProductResponse get(Long tenantId, Long id) {
        return ProductResponse.of(find(tenantId, id));
    }

    Product find(Long tenantId, Long id) {
        return products.findByIdAndTenantId(id, tenantId).orElseThrow(() -> new NotFoundException("product", id));
    }

    @Transactional
    public ProductResponse create(Long tenantId, ProductRequest req) {
        Tenant tenant = tenants.get(tenantId);
        if (products.existsByTenantIdAndCode(tenantId, req.code())) {
            throw new ConflictException("이미 있는 상품코드입니다: " + req.code());
        }
        Product p = new Product();
        p.setTenantId(tenantId);
        p.setCode(req.code());
        applyFields(p, req);
        applyNoticeDefaults(p, tenant);
        return ProductResponse.of(products.save(p));
    }

    @Transactional
    public ProductResponse update(Long tenantId, Long id, ProductRequest req) {
        Product p = editable(tenantId, id);
        // 상품코드는 이미지 폴더·파일명 매칭 키라서 바꾸지 않는다
        if (!p.getCode().equals(req.code())) {
            throw new ConflictException("상품코드는 변경할 수 없습니다");
        }
        applyFields(p, req);
        return ProductResponse.of(p);
    }

    @Transactional
    public void delete(Long tenantId, Long id) {
        Product p = find(tenantId, id);
        if (p.getStatus() == ProductStatus.APPROVED || p.getStatus() == ProductStatus.GENERATING) {
            throw new ConflictException("승인되었거나 생성 중인 상품은 삭제할 수 없습니다. 먼저 취소하세요");
        }
        products.delete(p);
    }

    @Transactional
    public ProductResponse replaceOptions(Long tenantId, Long id, List<OptionRequest> reqs) {
        Product p = editable(tenantId, id);
        Set<String> seen = new HashSet<>();
        List<ProductOption> next = new java.util.ArrayList<>();
        for (int i = 0; i < reqs.size(); i++) {
            OptionRequest r = reqs.get(i);
            if (!seen.add(r.color().trim() + "\u0000" + r.size().trim())) {
                throw new IllegalArgumentException("중복 옵션: " + r.color() + " / " + r.size());
            }
            ProductOption o = new ProductOption();
            o.setColor(r.color().trim());
            o.setSize(r.size().trim());
            o.setColorDisplay(blankToNull(r.colorDisplay()));
            o.setStock(r.stock());
            o.setExtraPrice(r.extraPrice());
            o.setSku(blankToNull(r.sku()));
            o.setSortOrder(i);
            next.add(o);
        }
        if (next.stream().anyMatch(o -> o.getColorDisplay() != null)) {
            p.getFieldSources().put(TextField.OPTION_DISPLAY, Source.MANUAL);
        }
        swapOptions(p, next);
        return ProductResponse.of(p);
    }

    @Transactional
    public ProductResponse combineOptions(Long tenantId, Long id, CombineRequest req) {
        Product p = editable(tenantId, id);
        swapOptions(p, OptionCombiner.combine(p.getCode(), req.colors(), req.sizes(), req.stock()));
        return ProductResponse.of(p);
    }

    @Transactional
    public ProductResponse replaceMeasurements(Long tenantId, Long id, List<MeasurementRequest> reqs) {
        Product p = editable(tenantId, id);
        Set<String> seen = new HashSet<>();
        List<ProductMeasurement> next = new java.util.ArrayList<>();
        for (MeasurementRequest r : reqs) {
            String size = r.size().trim();
            if (!seen.add(size)) {
                throw new IllegalArgumentException("같은 사이즈 실측이 두 번 들어왔습니다: " + size);
            }
            Map<String, BigDecimal> measures = new LinkedHashMap<>();
            r.measures().forEach((part, v) -> {
                if (part != null && !part.isBlank() && v != null) {
                    measures.put(part.trim(), v);
                }
            });
            ProductMeasurement m = new ProductMeasurement();
            m.setSize(size);
            m.setMeasures(measures);
            next.add(m);
        }
        p.getMeasurements().clear();
        products.flush(); // (product_id, size) 유니크 때문에 삭제를 먼저 내보낸다
        p.replaceMeasurements(next);
        return ProductResponse.of(p);
    }

    public List<ValidationIssue> validate(Long tenantId, Long id, ValidationPhase phase) {
        return validator.validate(find(tenantId, id), phase, tenants.get(tenantId).styleProfile());
    }

    /** 저장하지 않는 상품. 엑셀 미리보기 검증용으로 생성 시와 같은 규칙(기본값 채우기 포함)을 적용한다 */
    public static Product transientProduct(Tenant tenant, ProductRequest req) {
        Product p = new Product();
        p.setTenantId(tenant.getId());
        p.setCode(req.code());
        applyFields(p, req);
        applyNoticeDefaults(p, tenant);
        return p;
    }

    private Product editable(Long tenantId, Long id) {
        Product p = find(tenantId, id);
        if (!p.getStatus().editable()) {
            throw new ConflictException("현재 상태(" + p.getStatus() + ")에서는 수정할 수 없습니다");
        }
        return p;
    }

    private void swapOptions(Product p, List<ProductOption> next) {
        p.getOptions().clear();
        products.flush(); // (product_id, color, size) 유니크 때문에 삭제를 먼저 내보낸다
        p.replaceOptions(next);
    }

    private static void applyFields(Product p, ProductRequest req) {
        p.setCategory(blankToNull(req.category()));
        setText(p, TextField.NAME, p.getName(), blankToNull(req.name()), p::setName);
        setText(p, TextField.DESCRIPTION, p.getDescription(), blankToNull(req.description()), p::setDescription);
        List<String> keywords = req.searchKeywords() == null ? List.of()
                : req.searchKeywords().stream().map(String::trim).filter(s -> !s.isEmpty()).distinct().toList();
        setText(p, TextField.SEARCH_KEYWORDS, p.getSearchKeywords(), keywords,
                v -> p.setSearchKeywords(new java.util.ArrayList<>(v)));
        p.setSalePrice(req.salePrice());
        p.setMaterial(blankToNull(req.material()));
        p.setOriginCountry(blankToNull(req.originCountry()));
        p.setManufacturer(blankToNull(req.manufacturer()));
        p.setWashCare(blankToNull(req.washCare()));
        p.setKcCertification(blankToNull(req.kcCertification()));
        p.setManufacturedYm(blankToNull(req.manufacturedYm()));
        p.setQualityAssurance(blankToNull(req.qualityAssurance()));
        p.setAsManager(blankToNull(req.asManager()));
        p.setAsPhone(blankToNull(req.asPhone()));
    }

    /**
     * 문구가 바뀌었으면 사람이 고친 것으로 보고 출처를 MANUAL 로 바꾼다.
     * 그대로면 기존 출처(AI 포함)를 유지하고, 비우면 출처를 지워 큐에서 다시 생성되게 한다.
     */
    private static <T> void setText(Product p, TextField field, T current, T next, java.util.function.Consumer<T> setter) {
        boolean empty = next == null || (next instanceof List<?> l && l.isEmpty());
        if (empty) {
            p.getFieldSources().remove(field);
        } else if (!Objects.equals(current, next)) {
            p.getFieldSources().put(field, Source.MANUAL);
        }
        setter.accept(next);
    }

    /** 비어 있는 고시정보에 판매자 기본값을 채운다. 생성 시점에 한 번만 복사해 화면에서 보이게 한다 */
    private static void applyNoticeDefaults(Product p, Tenant t) {
        t.getNoticeDefaults().forEach((key, value) -> NoticeField.byKey(key).ifPresent(f -> {
            if (f.get(p) == null || f.get(p).isBlank()) {
                f.set(p, value);
            }
        }));
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
