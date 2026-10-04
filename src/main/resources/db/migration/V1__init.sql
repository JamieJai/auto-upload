-- 상품등록 자동화 플랫폼 초기 스키마.
-- 모든 상품 데이터는 tenant(판매자) 아래에 매달린다. 원본은 이 DB이고 채널은 내보내기 대상이다.

CREATE TABLE tenant (
    id                    BIGSERIAL PRIMARY KEY,
    code                  VARCHAR(32)  NOT NULL UNIQUE CHECK (code ~ '^[a-z0-9][a-z0-9-]{1,31}$'),
    name                  VARCHAR(100) NOT NULL,
    product_code_prefix   VARCHAR(16),
    brand_tone            TEXT,
    -- 고시정보 반복 항목 기본값. 키는 product 의 고시 컬럼명과 같다 (manufacturer, origin_country ...)
    notice_defaults       JSONB        NOT NULL DEFAULT '{}'::jsonb,
    allowed_image_domains TEXT[]       NOT NULL DEFAULT '{}',
    active                BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at            TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at            TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE channel_account (
    id               BIGSERIAL PRIMARY KEY,
    tenant_id        BIGINT       NOT NULL REFERENCES tenant (id),
    channel          VARCHAR(20)  NOT NULL CHECK (channel IN ('SMARTSTORE', 'CAFE24', 'ZIGZAG')),
    display_name     VARCHAR(100) NOT NULL,
    -- 애플리케이션 레벨 AES-GCM 암호문. 평문은 DB 에 남지 않는다
    credentials_enc  BYTEA,
    active           BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, channel),
    -- channel_listing 이 (id, tenant_id) 로 참조해 판매자 교차 등록을 DB 에서 막는다
    UNIQUE (id, tenant_id)
);

CREATE TABLE category_mapping (
    id                   BIGSERIAL PRIMARY KEY,
    tenant_id            BIGINT       NOT NULL REFERENCES tenant (id),
    channel              VARCHAR(20)  NOT NULL CHECK (channel IN ('SMARTSTORE', 'CAFE24', 'ZIGZAG')),
    category             VARCHAR(100) NOT NULL,
    channel_category_id  VARCHAR(50)  NOT NULL,
    UNIQUE (tenant_id, channel, category)
);

CREATE TABLE product (
    id                 BIGSERIAL PRIMARY KEY,
    tenant_id          BIGINT       NOT NULL REFERENCES tenant (id),
    code               VARCHAR(40)  NOT NULL CHECK (code ~ '^[A-Za-z0-9-]+$'),
    category           VARCHAR(100),
    status             VARCHAR(30)  NOT NULL DEFAULT 'DRAFT'
                       CHECK (status IN ('DRAFT', 'NEEDS_INPUT', 'GENERATING', 'PENDING_APPROVAL', 'APPROVED', 'CANCELLED')),

    -- 문구 (직접 입력 또는 AI 생성)
    name               VARCHAR(200),
    description        TEXT,
    search_keywords    TEXT[]       NOT NULL DEFAULT '{}',
    -- 필드별 출처: {"name": "MANUAL" | "AI"}
    field_sources      JSONB        NOT NULL DEFAULT '{}'::jsonb,

    -- 아래는 AI 생성 금지 항목. 입력값을 그대로 쓴다
    sale_price         INTEGER      CHECK (sale_price IS NULL OR sale_price > 0),
    material           TEXT,
    origin_country     VARCHAR(100),
    manufacturer       VARCHAR(200),
    wash_care          TEXT,
    kc_certification   VARCHAR(200),

    -- 상품정보제공고시 (의류) 나머지 항목
    manufactured_ym    VARCHAR(20),
    quality_assurance  TEXT,
    as_manager         VARCHAR(100),
    as_phone           VARCHAR(40),

    created_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, code),
    UNIQUE (id, tenant_id)
);
CREATE INDEX product_tenant_status_idx ON product (tenant_id, status);

CREATE TABLE product_option (
    id              BIGSERIAL PRIMARY KEY,
    product_id      BIGINT       NOT NULL REFERENCES product (id) ON DELETE CASCADE,
    color           VARCHAR(50)  NOT NULL,
    size            VARCHAR(30)  NOT NULL,
    -- 표시명은 AI 생성 가능 (예: "크림 아이보리"). 실제 값(color/size)은 입력값
    color_display   VARCHAR(100),
    stock           INTEGER      NOT NULL DEFAULT 0 CHECK (stock >= 0),
    extra_price     INTEGER      NOT NULL DEFAULT 0,
    sku             VARCHAR(60),
    sort_order      INTEGER      NOT NULL DEFAULT 0,
    UNIQUE (product_id, color, size)
);

CREATE TABLE product_measurement (
    id          BIGSERIAL PRIMARY KEY,
    product_id  BIGINT      NOT NULL REFERENCES product (id) ON DELETE CASCADE,
    size        VARCHAR(30) NOT NULL,
    -- 부위별 실측(cm): {"총장": 68.5, "가슴단면": 52}
    measures    JSONB       NOT NULL DEFAULT '{}'::jsonb,
    UNIQUE (product_id, size)
);

CREATE TABLE product_image (
    id           BIGSERIAL PRIMARY KEY,
    product_id   BIGINT       NOT NULL REFERENCES product (id) ON DELETE CASCADE,
    slot         VARCHAR(10)  NOT NULL CHECK (slot IN ('main', 'sub', 'detail', 'size')),
    seq          INTEGER      NOT NULL CHECK (seq >= 1),
    path         VARCHAR(500) NOT NULL,
    thumb_path   VARCHAR(500),
    source_type  VARCHAR(10)  NOT NULL CHECK (source_type IN ('UPLOAD', 'WEB')),
    source_url   TEXT,
    width        INTEGER,
    height       INTEGER,
    sha256       VARCHAR(64),
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    UNIQUE (product_id, slot, seq)
);

CREATE TABLE channel_image (
    id                  BIGSERIAL PRIMARY KEY,
    product_image_id    BIGINT      NOT NULL REFERENCES product_image (id) ON DELETE CASCADE,
    channel_account_id  BIGINT      NOT NULL REFERENCES channel_account (id),
    channel_url         TEXT        NOT NULL,
    uploaded_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (product_image_id, channel_account_id)
);

CREATE TABLE channel_listing (
    id                  BIGSERIAL PRIMARY KEY,
    tenant_id           BIGINT      NOT NULL,
    product_id          BIGINT      NOT NULL,
    channel_account_id  BIGINT      NOT NULL,
    status              VARCHAR(20) NOT NULL DEFAULT 'PENDING'
                        CHECK (status IN ('PENDING', 'REGISTERING', 'COMPLETED', 'FAILED_RETRYABLE', 'FAILED_INVALID', 'CANCELLED')),
    channel_product_no  VARCHAR(50),
    idempotency_key     UUID        NOT NULL UNIQUE,
    last_response       JSONB,
    registered_at       TIMESTAMPTZ,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (product_id, channel_account_id),
    -- 상품과 채널 계정이 같은 판매자일 때만 행이 생긴다
    FOREIGN KEY (product_id, tenant_id) REFERENCES product (id, tenant_id) ON DELETE CASCADE,
    FOREIGN KEY (channel_account_id, tenant_id) REFERENCES channel_account (id, tenant_id)
);

CREATE TABLE job (
    id                  BIGSERIAL PRIMARY KEY,
    tenant_id           BIGINT      NOT NULL REFERENCES tenant (id),
    product_id          BIGINT      NOT NULL,
    channel_listing_id  BIGINT      REFERENCES channel_listing (id) ON DELETE CASCADE,
    type                VARCHAR(20) NOT NULL CHECK (type IN ('GENERATE', 'REGISTER')),
    status              VARCHAR(20) NOT NULL DEFAULT 'QUEUED'
                        CHECK (status IN ('QUEUED', 'RUNNING', 'SUCCEEDED', 'FAILED_RETRYABLE', 'FAILED_INVALID', 'CANCELLED')),
    step                VARCHAR(40),
    attempt             INTEGER     NOT NULL DEFAULT 0,
    next_run_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_error          TEXT,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    FOREIGN KEY (product_id, tenant_id) REFERENCES product (id, tenant_id) ON DELETE CASCADE
);
CREATE INDEX job_runnable_idx ON job (next_run_at) WHERE status IN ('QUEUED', 'FAILED_RETRYABLE');
CREATE INDEX job_tenant_created_idx ON job (tenant_id, created_at DESC);

CREATE TABLE job_log (
    id          BIGSERIAL PRIMARY KEY,
    job_id      BIGINT      NOT NULL REFERENCES job (id) ON DELETE CASCADE,
    step        VARCHAR(40),
    level       VARCHAR(10) NOT NULL CHECK (level IN ('INFO', 'WARN', 'ERROR')),
    message     TEXT        NOT NULL,
    payload     JSONB,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX job_log_job_idx ON job_log (job_id, id);
