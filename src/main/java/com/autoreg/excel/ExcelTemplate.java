package com.autoreg.excel;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;

import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

/** 업로드 템플릿. '상품'·'실측' 시트는 비워 두고, 작성 예시는 '예시' 시트에 둔다 (예시가 등록되지 않게). */
public final class ExcelTemplate {

    public static final String PRODUCT_SHEET = "상품";
    public static final String MEASURE_SHEET = "실측";
    public static final String EXAMPLE_SHEET = "예시";
    public static final List<String> MEASURE_PARTS =
            List.of("총장", "어깨너비", "가슴단면", "소매길이", "허리단면", "엉덩이단면", "허벅지단면", "밑위", "밑단");

    private ExcelTemplate() {}

    public static byte[] build() {
        try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            CellStyle head = wb.createCellStyle();
            Font bold = wb.createFont();
            bold.setBold(true);
            head.setFont(bold);
            head.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
            head.setFillPattern(FillPatternType.SOLID_FOREGROUND);

            Sheet products = wb.createSheet(PRODUCT_SHEET);
            Row h = products.createRow(0);
            ExcelColumn[] cols = ExcelColumn.values();
            for (int i = 0; i < cols.length; i++) {
                h.createCell(i).setCellValue(cols[i].displayHeader());
                h.getCell(i).setCellStyle(head);
                products.setColumnWidth(i, 18 * 256);
            }
            products.createFreezePane(1, 1);

            Sheet measures = wb.createSheet(MEASURE_SHEET);
            Row mh = measures.createRow(0);
            mh.createCell(0).setCellValue("상품코드");
            mh.createCell(1).setCellValue("사이즈");
            for (int i = 0; i < MEASURE_PARTS.size(); i++) {
                mh.createCell(i + 2).setCellValue(MEASURE_PARTS.get(i));
            }
            mh.forEach(c -> c.setCellStyle(head));
            measures.createFreezePane(2, 1);

            Sheet ex = wb.createSheet(EXAMPLE_SHEET);
            Row eh = ex.createRow(0);
            Row ev = ex.createRow(1);
            for (int i = 0; i < cols.length; i++) {
                eh.createCell(i).setCellValue(cols[i].displayHeader());
                eh.getCell(i).setCellStyle(head);
                ev.createCell(i).setCellValue(cols[i].example());
                ex.setColumnWidth(i, 18 * 256);
            }
            String[][] notes = {
                    {"* 표시는 필수입니다. 색상·사이즈·검색키워드는 쉼표로 구분합니다."},
                    {"색상×사이즈 조합마다 옵션이 만들어지고, 재고는 옵션마다 같은 값으로 들어갑니다."},
                    {"비워 둔 고시정보는 판매자 기본값으로 채워집니다. 상품명·상세설명을 비우면 AI 가 생성합니다."},
                    {"실측 시트: 한 줄에 상품코드+사이즈 하나. 부위 컬럼은 추가해도 됩니다. 단위 cm."},
                    {"이미지는 엑셀이 아니라 파일명 규칙({상품코드}_{슬롯}_{순번}.jpg)으로 따로 올립니다."}};
            for (int i = 0; i < notes.length; i++) {
                ex.createRow(3 + i).createCell(0).setCellValue(notes[i][0]);
            }
            wb.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
