package com.autoreg.intake;

import java.time.OffsetDateTime;
import java.util.Map;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** 도매처 페이지 원문. AI 추출의 근거이자 사람이 대조할 자료 */
@Entity
@Getter
@Setter
@NoArgsConstructor
public class ProductSource {

    @Id
    private Long productId;

    private String sourceUrl;

    private String title;

    private String rawText;

    /** AI 가 뽑은 값 그대로 (감사용) */
    @JdbcTypeCode(SqlTypes.JSON)
    private Map<String, Object> extracted;

    /** 원문과 대조해 버린 값 등 사람이 봐야 할 메모 */
    private String notes;

    private OffsetDateTime capturedAt = OffsetDateTime.now();

    private OffsetDateTime extractedAt;
}
