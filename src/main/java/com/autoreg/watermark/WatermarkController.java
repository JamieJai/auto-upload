package com.autoreg.watermark;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import lombok.RequiredArgsConstructor;

@RestController
@RequiredArgsConstructor
public class WatermarkController {

    private final WatermarkService service;
    private final com.autoreg.job.JobService jobs;
    private final com.autoreg.product.ProductRepository products;

    public record Queued(Long jobId) {}

    public record CreateRequest(@NotBlank @Pattern(regexp = "^[a-z0-9_-]{2,60}$", message = "영문 소문자·숫자·_- 2~60자") String name,
            @NotNull Long tenantId, @NotNull Long productId) {}

    /** redo: 이미 지운 사진도 원본에서 다시 (리터치가 바뀌었을 때) */
    public record ApplyRequest(@NotBlank String template, Boolean redo) {}

    @GetMapping("/api/watermarks")
    public List<ImagingClient.Template> list() {
        return service.templates();
    }

    /** 확장용 (토큰 인증) */
    @GetMapping("/api/intake/watermarks")
    public List<ImagingClient.Template> listForExtension() {
        return service.templates();
    }

    @PostMapping("/api/watermarks")
    public ImagingClient.Template create(@Valid @RequestBody CreateRequest req) {
        return service.createTemplate(req.name(), req.tenantId(), req.productId());
    }

    /** 사진당 20초 안팎이라 워커 작업으로 넘긴다. 진행은 작업 상세에서 본다 */
    @PostMapping("/api/tenants/{tenantId}/products/{id}/watermark")
    public Queued apply(@PathVariable Long tenantId, @PathVariable Long id, @Valid @RequestBody ApplyRequest req) {
        products.findByIdAndTenantId(id, tenantId).orElseThrow(() -> new com.autoreg.common.NotFoundException("product", id));
        if (service.templates().stream().noneMatch(t -> t.name().equals(req.template()))) {
            throw new IllegalArgumentException("워터마크 템플릿이 없습니다: " + req.template());
        }
        return new Queued(jobs.enqueue(tenantId, id, com.autoreg.job.JobType.WATERMARK, null,
                java.util.Map.of("template", req.template(), "redo", Boolean.TRUE.equals(req.redo()))).getId());
    }

    @PostMapping("/api/tenants/{tenantId}/products/{id}/images/{imageId}/restore")
    public void restore(@PathVariable Long tenantId, @PathVariable Long id, @PathVariable Long imageId) {
        service.restore(tenantId, id, imageId);
    }
}
