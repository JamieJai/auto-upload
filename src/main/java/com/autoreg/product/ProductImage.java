package com.autoreg.product;

import java.time.OffsetDateTime;

import org.hibernate.annotations.CreationTimestamp;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Getter
@Setter
@NoArgsConstructor
public class ProductImage {

    public enum SourceType { UPLOAD, WEB }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "product_id")
    private Product product;

    private ImageSlot slot;

    private int seq;

    /** /data/images 기준 상대경로: {판매자코드}/{상품코드}/main_01.jpg */
    private String path;

    private String thumbPath;

    @Enumerated(EnumType.STRING)
    private SourceType sourceType;

    private String sourceUrl;

    private Integer width;

    private Integer height;

    private String sha256;

    @CreationTimestamp
    private OffsetDateTime createdAt;
}
