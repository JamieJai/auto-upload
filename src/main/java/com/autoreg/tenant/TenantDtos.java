package com.autoreg.tenant;

import java.util.List;
import java.util.Map;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public final class TenantDtos {

    private TenantDtos() {}

    public record TenantRequest(
            @NotBlank @Pattern(regexp = "^[a-z0-9][a-z0-9-]{1,31}$", message = "영문 소문자·숫자·하이픈 2~32자") String code,
            @NotBlank @Size(max = 100) String name,
            @Size(max = 16) String productCodePrefix,
            String brandTone,
            Map<String, String> noticeDefaults,
            List<String> allowedImageDomains,
            Boolean active) {}

    public record TenantResponse(
            Long id,
            String code,
            String name,
            String productCodePrefix,
            String brandTone,
            Map<String, String> noticeDefaults,
            List<String> allowedImageDomains,
            boolean active) {

        static TenantResponse of(Tenant t) {
            return new TenantResponse(t.getId(), t.getCode(), t.getName(), t.getProductCodePrefix(), t.getBrandTone(),
                    t.getNoticeDefaults(), t.getAllowedImageDomains(), t.isActive());
        }
    }
}
