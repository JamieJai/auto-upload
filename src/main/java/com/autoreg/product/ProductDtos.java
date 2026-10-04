package com.autoreg.product;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import com.autoreg.product.validation.ValidationIssue;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public final class ProductDtos {

    private ProductDtos() {}

    /** 생성·수정 공용. 옵션·실측은 별도 엔드포인트로 통째로 교체한다 */
    public record ProductRequest(
            @NotBlank @Pattern(regexp = "^[A-Za-z0-9-]{1,40}$", message = "영문·숫자·하이픈 40자 이하") String code,
            @Size(max = 100) String category,
            @Size(max = 200) String name,
            String description,
            @Size(max = 30) List<@NotBlank @Size(max = 50) String> searchKeywords,
            @Positive Integer salePrice,
            String material,
            @Size(max = 100) String originCountry,
            @Size(max = 200) String manufacturer,
            String washCare,
            @Size(max = 200) String kcCertification,
            @Size(max = 20) String manufacturedYm,
            String qualityAssurance,
            @Size(max = 100) String asManager,
            @Size(max = 40) String asPhone) {}

    public record OptionRequest(
            @NotBlank @Size(max = 50) String color,
            @NotBlank @Size(max = 30) String size,
            @Size(max = 100) String colorDisplay,
            @Min(0) int stock,
            int extraPrice,
            @Size(max = 60) String sku) {}

    public record OptionsRequest(@NotNull @Valid List<OptionRequest> options) {}

    public record CombineRequest(@NotNull List<String> colors, @NotNull List<String> sizes, @Min(0) int stock) {}

    public record MeasurementRequest(@NotBlank @Size(max = 30) String size, @NotNull Map<String, BigDecimal> measures) {}

    public record MeasurementsRequest(@NotNull @Valid List<MeasurementRequest> measurements) {}

    public record OptionResponse(Long id, String color, String size, String colorDisplay, int stock, int extraPrice,
            String sku) {
        static OptionResponse of(ProductOption o) {
            return new OptionResponse(o.getId(), o.getColor(), o.getSize(), o.getColorDisplay(), o.getStock(),
                    o.getExtraPrice(), o.getSku());
        }
    }

    public record MeasurementResponse(String size, Map<String, BigDecimal> measures) {
        static MeasurementResponse of(ProductMeasurement m) {
            return new MeasurementResponse(m.getSize(), m.getMeasures());
        }
    }

    public record ImageResponse(Long id, String slot, int seq, String path, String thumbPath, String sourceType,
            String sourceUrl, Integer width, Integer height) {
        static ImageResponse of(ProductImage i) {
            return new ImageResponse(i.getId(), i.getSlot().value(), i.getSeq(), i.getPath(), i.getThumbPath(),
                    i.getSourceType().name(), i.getSourceUrl(), i.getWidth(), i.getHeight());
        }
    }

    public record ProductSummary(Long id, String code, String name, String category, ProductStatus status,
            Integer salePrice, OffsetDateTime updatedAt) {
        static ProductSummary of(Product p) {
            return new ProductSummary(p.getId(), p.getCode(), p.getName(), p.getCategory(), p.getStatus(),
                    p.getSalePrice(), p.getUpdatedAt());
        }
    }

    public record ProductResponse(
            Long id,
            Long tenantId,
            String code,
            String category,
            ProductStatus status,
            String name,
            String description,
            List<String> searchKeywords,
            Map<TextField, TextField.Source> fieldSources,
            Integer salePrice,
            Map<String, String> notice,
            List<OptionResponse> options,
            List<MeasurementResponse> measurements,
            List<ImageResponse> images,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt) {

        static ProductResponse of(Product p) {
            Map<String, String> notice = new java.util.LinkedHashMap<>();
            for (NoticeField f : NoticeField.values()) {
                notice.put(f.key(), f.get(p));
            }
            return new ProductResponse(p.getId(), p.getTenantId(), p.getCode(), p.getCategory(), p.getStatus(),
                    p.getName(), p.getDescription(), p.getSearchKeywords(), p.getFieldSources(), p.getSalePrice(),
                    notice,
                    p.getOptions().stream().map(OptionResponse::of).toList(),
                    p.getMeasurements().stream().map(MeasurementResponse::of).toList(),
                    p.getImages().stream().map(ImageResponse::of).toList(),
                    p.getCreatedAt(), p.getUpdatedAt());
        }
    }

    public record ValidationResponse(boolean ok, List<ValidationIssue> issues) {}
}
