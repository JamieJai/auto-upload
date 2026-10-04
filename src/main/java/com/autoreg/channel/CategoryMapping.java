package com.autoreg.channel;

import java.time.OffsetDateTime;
import java.util.Map;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** 판매자 카테고리(예: "원피스") → 채널 카테고리 ID */
@Entity
@Getter
@Setter
@NoArgsConstructor
public class CategoryMapping {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(updatable = false)
    private Long tenantId;

    @Enumerated(EnumType.STRING)
    private Channel channel;

    private String category;

    private String channelCategoryId;

    /** 레퍼런스 원상품번호 (스마트스토어) */
    private String referenceProductNo;

    private String referenceName;

    /** SmartStoreReference.snapshot 결과. 없으면 채널 계정 settings 의 템플릿을 쓴다 */
    @JdbcTypeCode(SqlTypes.JSON)
    private Map<String, Object> reference;

    private OffsetDateTime referenceFetchedAt;
}
