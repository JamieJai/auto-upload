package com.autoreg.product;

import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** 색상×사이즈 조합 1개. SKU 단위. */
@Entity
@Getter
@Setter
@NoArgsConstructor
public class ProductOption {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "product_id")
    private Product product;

    private String color;

    private String size;

    /** AI 생성 가능한 표시명. 비어 있으면 color 를 그대로 쓴다 */
    private String colorDisplay;

    private int stock;

    private int extraPrice;

    private String sku;

    private int sortOrder;
}
