package com.autoreg.job;

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

@Entity
@Getter
@Setter
@NoArgsConstructor
public class Job {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(updatable = false)
    private Long tenantId;

    @Column(updatable = false)
    private Long productId;

    @Column(updatable = false)
    private Long channelListingId;

    @Enumerated(EnumType.STRING)
    @Column(updatable = false)
    private JobType type;

    @Enumerated(EnumType.STRING)
    private JobStatus status = JobStatus.QUEUED;

    private String step;

    /** 실행 시작 횟수. 워커가 꺼낼 때 1 올린다 */
    private int attempt;

    private OffsetDateTime nextRunAt = OffsetDateTime.now();

    private String lastError;

    @CreationTimestamp
    private OffsetDateTime createdAt;

    @UpdateTimestamp
    private OffsetDateTime updatedAt;
}
