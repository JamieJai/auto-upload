-- 품목(카테고리)별 레퍼런스 상품. 같은 판매자·같은 품목의 기존 상품에서 판매 설정을 복제한다
ALTER TABLE category_mapping ADD COLUMN reference_product_no VARCHAR(30);
ALTER TABLE category_mapping ADD COLUMN reference_name VARCHAR(200);
-- 신규 등록에 쓸 수 있게 정리한 스냅샷 (읽기 전용 ID·상품별 값 제거)
ALTER TABLE category_mapping ADD COLUMN reference JSONB;
ALTER TABLE category_mapping ADD COLUMN reference_fetched_at TIMESTAMPTZ;
