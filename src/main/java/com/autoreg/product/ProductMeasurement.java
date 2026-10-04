package com.autoreg.product;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

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

/** 사이즈별 실측(cm). AI 생성 금지. */
@Entity
@Getter
@Setter
@NoArgsConstructor
public class ProductMeasurement {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "product_id")
    private Product product;

    private String size;

    /** 부위 → cm. 입력 순서를 유지한다 */
    @JdbcTypeCode(SqlTypes.JSON)
    private Map<String, BigDecimal> measures = new LinkedHashMap<>();
}
