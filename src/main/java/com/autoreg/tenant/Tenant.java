package com.autoreg.tenant;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** 판매자. 권한 단위가 아니라 데이터가 섞이지 않게 하는 구분 단위다. */
@Entity
@Getter
@Setter
@NoArgsConstructor
public class Tenant {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 폴더명에도 쓰인다: /data/images/{code}/... */
    private String code;

    private String name;

    private String productCodePrefix;

    private String brandTone;

    @JdbcTypeCode(SqlTypes.JSON)
    private Map<String, String> noticeDefaults = new HashMap<>();

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(columnDefinition = "text[]")
    private List<String> allowedImageDomains = new ArrayList<>();

    /** 확장으로 받은 상품의 판매가·기본 재고 규칙 (PriceRule 참고). 없으면 판매가는 사람이 넣는다 */
    @JdbcTypeCode(SqlTypes.JSON)
    private Map<String, Object> priceRule;

    private boolean active = true;

    @CreationTimestamp
    private OffsetDateTime createdAt;

    @UpdateTimestamp
    private OffsetDateTime updatedAt;
}
