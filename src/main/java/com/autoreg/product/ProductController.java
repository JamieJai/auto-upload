package com.autoreg.product;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.autoreg.product.ProductDtos.CombineRequest;
import com.autoreg.product.ProductDtos.MeasurementsRequest;
import com.autoreg.product.ProductDtos.OptionsRequest;
import com.autoreg.product.ProductDtos.ProductRequest;
import com.autoreg.product.ProductDtos.ProductResponse;
import com.autoreg.product.ProductDtos.ProductSummary;
import com.autoreg.product.ProductDtos.ValidationResponse;
import com.autoreg.product.validation.ValidationPhase;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/** 모든 상품 API 는 판매자 경로 아래에 있다. 다른 판매자의 상품 id 를 넣으면 404 다. */
@RestController
@RequestMapping("/api/tenants/{tenantId}/products")
@RequiredArgsConstructor
public class ProductController {

    private final ProductService service;

    @GetMapping
    public Page<ProductSummary> list(@PathVariable Long tenantId,
            @RequestParam(required = false) ProductStatus status,
            @PageableDefault(size = 20, sort = "updatedAt", direction = Sort.Direction.DESC) Pageable pageable) {
        return service.list(tenantId, status, pageable).map(ProductSummary::of);
    }

    @GetMapping("/{id}")
    public ProductResponse get(@PathVariable Long tenantId, @PathVariable Long id) {
        return service.get(tenantId, id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ProductResponse create(@PathVariable Long tenantId, @Valid @RequestBody ProductRequest req) {
        return service.create(tenantId, req);
    }

    @PutMapping("/{id}")
    public ProductResponse update(@PathVariable Long tenantId, @PathVariable Long id,
            @Valid @RequestBody ProductRequest req) {
        return service.update(tenantId, id, req);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long tenantId, @PathVariable Long id) {
        service.delete(tenantId, id);
    }

    @PutMapping("/{id}/options")
    public ProductResponse replaceOptions(@PathVariable Long tenantId, @PathVariable Long id,
            @Valid @RequestBody OptionsRequest req) {
        return service.replaceOptions(tenantId, id, req.options());
    }

    /** 색상×사이즈 목록으로 옵션을 새로 만든다. 기존 옵션은 지워진다 */
    @PostMapping("/{id}/options/combine")
    public ProductResponse combineOptions(@PathVariable Long tenantId, @PathVariable Long id,
            @Valid @RequestBody CombineRequest req) {
        return service.combineOptions(tenantId, id, req);
    }

    @PutMapping("/{id}/measurements")
    public ProductResponse replaceMeasurements(@PathVariable Long tenantId, @PathVariable Long id,
            @Valid @RequestBody MeasurementsRequest req) {
        return service.replaceMeasurements(tenantId, id, req.measurements());
    }

    @GetMapping("/{id}/validation")
    public ValidationResponse validate(@PathVariable Long tenantId, @PathVariable Long id,
            @RequestParam(defaultValue = "INPUT") ValidationPhase phase) {
        var issues = service.validate(tenantId, id, phase);
        return new ValidationResponse(issues.isEmpty(), issues);
    }
}
