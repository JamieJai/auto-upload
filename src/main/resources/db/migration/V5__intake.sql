-- 브라우저 확장으로 도매처 페이지에서 상품 초안 받기

-- 판매가 규칙: {"multiplier":2.0,"add":0,"roundUnit":1000,"subtract":100} → 도매가×2, 천원 올림, -100
ALTER TABLE tenant ADD COLUMN price_rule JSONB;

-- 확장 프로그램용 토큰. 원문은 발급 때 한 번만 보여 주고 해시만 저장한다
CREATE TABLE intake_token (
    id            BIGSERIAL PRIMARY KEY,
    name          VARCHAR(100) NOT NULL,
    token_hash    VARCHAR(64)  NOT NULL UNIQUE,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    last_used_at  TIMESTAMPTZ,
    revoked_at    TIMESTAMPTZ
);

-- 상품의 원문 (도매처 페이지 글자). AI 추출의 근거이자 사람이 대조할 자료
CREATE TABLE product_source (
    product_id    BIGINT       PRIMARY KEY REFERENCES product (id) ON DELETE CASCADE,
    source_url    TEXT,
    title         TEXT,
    raw_text      TEXT         NOT NULL,
    extracted     JSONB,
    notes         TEXT,
    captured_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    extracted_at  TIMESTAMPTZ
);

ALTER TABLE job DROP CONSTRAINT job_type_check;
ALTER TABLE job ADD CONSTRAINT job_type_check CHECK (type IN ('GENERATE', 'REGISTER', 'EXTRACT'));
