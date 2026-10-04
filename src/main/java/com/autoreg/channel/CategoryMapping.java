package com.autoreg.channel;

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
}
