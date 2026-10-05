-- 특성(스타일 프로필): 쇼핑몰마다 다른 등록 규칙 (문구·태그·옵션·이미지·상세 구성·등록 기본값).
-- 다른 판매자에서 복사해 오며, 복사 후에는 각자 따로 고친다. 원본을 기록해 두고 다시 덮어쓸 수 있다
ALTER TABLE tenant ADD COLUMN style JSONB NOT NULL DEFAULT '{}'::jsonb;
ALTER TABLE tenant ADD COLUMN style_source_tenant_id BIGINT REFERENCES tenant (id) ON DELETE SET NULL;
ALTER TABLE tenant ADD COLUMN style_copied_at TIMESTAMPTZ;
