package com.autoreg.job;

import java.time.OffsetDateTime;
import java.util.Map;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.Entity;
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
public class JobLog {

    public enum Level { INFO, WARN, ERROR }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long jobId;

    private String step;

    @jakarta.persistence.Enumerated(jakarta.persistence.EnumType.STRING)
    private Level level;

    private String message;

    @JdbcTypeCode(SqlTypes.JSON)
    private Map<String, Object> payload;

    @CreationTimestamp
    private OffsetDateTime createdAt;
}
