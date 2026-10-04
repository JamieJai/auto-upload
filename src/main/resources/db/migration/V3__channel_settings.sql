-- 채널 계정의 비밀이 아닌 설정 (배송·A/S·원산지 템플릿, dryRun, 전시 상태 등). 인증정보는 credentials_enc 에만 둔다
ALTER TABLE channel_account ADD COLUMN settings JSONB NOT NULL DEFAULT '{}'::jsonb;
