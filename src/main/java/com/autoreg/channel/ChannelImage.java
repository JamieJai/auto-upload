package com.autoreg.channel;

import java.time.OffsetDateTime;

import org.hibernate.annotations.CreationTimestamp;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** 채널에 이미 올린 이미지 URL. 재시도 때 다시 올리지 않는다 */
@Entity
@Getter
@Setter
@NoArgsConstructor
public class ChannelImage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long productImageId;

    private Long channelAccountId;

    private String channelUrl;

    @CreationTimestamp
    private OffsetDateTime uploadedAt;
}
