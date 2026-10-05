-- 워터마크 제거: 처리한 사진은 원본을 따로 보관해 되돌릴 수 있게 한다
ALTER TABLE product_image ADD COLUMN original_path VARCHAR(500);
ALTER TABLE product_image ADD COLUMN watermark_template VARCHAR(60);
-- 확장으로 받을 때 고른 템플릿 (워커가 원문 추출 전에 적용)
ALTER TABLE product_source ADD COLUMN watermark_template VARCHAR(60);
