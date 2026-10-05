-- 작업별 인자 (워터마크 템플릿 이름 등)
ALTER TABLE job ADD COLUMN params JSONB;
ALTER TABLE job DROP CONSTRAINT job_type_check;
ALTER TABLE job ADD CONSTRAINT job_type_check CHECK (type IN ('GENERATE', 'REGISTER', 'EXTRACT', 'WATERMARK'));
