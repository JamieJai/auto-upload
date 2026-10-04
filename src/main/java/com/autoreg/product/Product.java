package com.autoreg.product;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Getter
@Setter
@NoArgsConstructor
public class Product {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(updatable = false)
    private Long tenantId;

    /** 판매자 상품코드. 이미지 파일명 매칭 키다 */
    private String code;

    private String category;

    @Enumerated(EnumType.STRING)
    private ProductStatus status = ProductStatus.DRAFT;

    private String name;

    private String description;

    /** 반려 사유 */
    private String reviewNote;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(columnDefinition = "text[]")
    private List<String> searchKeywords = new ArrayList<>();

    @JdbcTypeCode(SqlTypes.JSON)
    private Map<TextField, TextField.Source> fieldSources = new HashMap<>();

    // ---- AI 생성 금지 항목 ----
    private Integer salePrice;
    private String material;
    private String originCountry;
    private String manufacturer;
    private String washCare;
    @Column(name = "kc_certification")
    private String kcCertification;

    // ---- 고시정보 나머지 ----
    private String manufacturedYm;
    private String qualityAssurance;
    private String asManager;
    private String asPhone;

    @OneToMany(mappedBy = "product", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("sortOrder ASC, id ASC")
    private List<ProductOption> options = new ArrayList<>();

    @OneToMany(mappedBy = "product", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("id ASC")
    private List<ProductMeasurement> measurements = new ArrayList<>();

    @OneToMany(mappedBy = "product", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("slot ASC, seq ASC")
    private List<ProductImage> images = new ArrayList<>();

    @CreationTimestamp
    private OffsetDateTime createdAt;

    @UpdateTimestamp
    private OffsetDateTime updatedAt;

    public void replaceOptions(List<ProductOption> next) {
        options.clear();
        next.forEach(o -> {
            o.setProduct(this);
            options.add(o);
        });
    }

    public void replaceMeasurements(List<ProductMeasurement> next) {
        measurements.clear();
        next.forEach(m -> {
            m.setProduct(this);
            measurements.add(m);
        });
    }

    public void addImage(ProductImage image) {
        image.setProduct(this);
        images.add(image);
    }
}
