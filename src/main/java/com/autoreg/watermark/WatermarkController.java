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

    public record CreateRequest(@NotBlank @Pattern(regexp = "^[a-z0-9_-]{2,60}$", message = "영문 소문자·숫자·_- 2~60자") String name,
            @NotNull Long tenantId, @NotNull Long productId) {}

    public record ApplyRequest(@NotBlank String template) {}

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

    @PostMapping("/api/tenants/{tenantId}/products/{id}/watermark")
    public WatermarkService.Applied apply(@PathVariable Long tenantId, @PathVariable Long id, @Valid @RequestBody ApplyRequest req) {
        return service.apply(tenantId, id, req.template());
    }

    @PostMapping("/api/tenants/{tenantId}/products/{id}/images/{imageId}/restore")
    public void restore(@PathVariable Long tenantId, @PathVariable Long id, @PathVariable Long imageId) {
        service.restore(tenantId, id, imageId);
    }
}
