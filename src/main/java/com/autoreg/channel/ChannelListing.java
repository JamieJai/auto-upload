package com.autoreg.channel;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
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

/** 상품 1건 × 채널 계정 1개의 등록 결과. 재승인해도 같은 행과 멱등성 키를 다시 쓴다 */
@Entity
@Getter
@Setter
@NoArgsConstructor
public class ChannelListing {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(updatable = false)
    private Long tenantId;

    @Column(updatable = false)
    private Long productId;

    @Column(updatable = false)
    private Long channelAccountId;

    @Enumerated(EnumType.STRING)
    private ListingStatus status = ListingStatus.PENDING;

    private String channelProductNo;

    @Column(updatable = false)
    private UUID idempotencyKey = UUID.randomUUID();

    @JdbcTypeCode(SqlTypes.JSON)
    private Map<String, Object> lastResponse;

    private OffsetDateTime registeredAt;

    @CreationTimestamp
    private OffsetDateTime createdAt;

    @UpdateTimestamp
    private OffsetDateTime updatedAt;
}
