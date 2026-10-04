package com.autoreg.excel;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;

import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

/** 템플릿을 받아 행을 채운 파일을 만든다 (사용자가 하는 그대로) */
public final class ExcelTestFiles {

    private ExcelTestFiles() {}

    public static byte[] filled(Object[][] productRows, Object[][] measureRows) throws Exception {
        try (Workbook wb = new XSSFWorkbook(new ByteArrayInputStream(ExcelTemplate.build()));
                ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            fill(wb.getSheet(ExcelTemplate.PRODUCT_SHEET), productRows);
            fill(wb.getSheet(ExcelTemplate.MEASURE_SHEET), measureRows);
            wb.write(out);
            return out.toByteArray();
        }
    }

    private static void fill(Sheet sheet, Object[][] rows) {
        for (int r = 0; r < rows.length; r++) {
            Row row = sheet.createRow(r + 1);
            for (int c = 0; c < rows[r].length; c++) {
                Object v = rows[r][c];
                if (v instanceof Number n) {
                    row.createCell(c).setCellValue(n.doubleValue());
                } else if (v != null) {
                    row.createCell(c).setCellValue(v.toString());
                }
            }
        }
    }

    /** 템플릿 컬럼 순서: 코드, 카테고리, 판매가, 색상, 사이즈, 재고, 상품명, 상세설명, 키워드, 소재 ... */
    public static Object[] product(String code, Object price, String colors, String sizes, Object stock, String material) {
        return new Object[] {code, "원피스", price, colors, sizes, stock, null, null, "린넨, 원피스", material};
    }
}
