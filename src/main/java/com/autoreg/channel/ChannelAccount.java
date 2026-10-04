package com.autoreg.channel;

import java.time.OffsetDateTime;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

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

/** 판매자의 채널 계정. 판매자당 채널 1개. */
@Entity
@Getter
@Setter
@NoArgsConstructor
public class ChannelAccount {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(updatable = false)
    private Long tenantId;

    @Enumerated(EnumType.STRING)
    @Column(updatable = false)
    private Channel channel;

    private String displayName;

    /** CredentialCipher 로 암호화한 JSON. 응답에 절대 싣지 않는다 */
    private byte[] credentialsEnc;

    private boolean active = true;

    @CreationTimestamp
    private OffsetDateTime createdAt;

    @UpdateTimestamp
    private OffsetDateTime updatedAt;
}
