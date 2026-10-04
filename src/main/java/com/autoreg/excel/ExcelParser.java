package com.autoreg.excel;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;

import com.autoreg.product.ProductDtos.ProductRequest;

/**
 * 템플릿 엑셀을 읽어 행 단위로 돌려준다. 형식 오류는 행의 errors 에 담고 예외를 던지지 않는다.
 * 파일 자체를 못 읽을 때만 IllegalArgumentException.
 */
public final class ExcelParser {

    public static final int MAX_ROWS = 1000;

    private static final DataFormatter FMT = new DataFormatter();

    private ExcelParser() {}

    public record Parsed(List<RawRow> rows, List<String> notes) {}

    public record RawRow(int rowNumber, ProductRequest product, List<String> colors, List<String> sizes, int stock,
            Map<String, Map<String, BigDecimal>> measurements, List<String> errors) {}

    public static Parsed parse(byte[] bytes) {
        try (Workbook wb = WorkbookFactory.create(new ByteArrayInputStream(bytes))) {
            Sheet sheet = wb.getSheet(ExcelTemplate.PRODUCT_SHEET);
            if (sheet == null) {
                throw new IllegalArgumentException("'" + ExcelTemplate.PRODUCT_SHEET + "' 시트가 없습니다. 템플릿을 받아 사용하세요");
            }
            List<String> notes = new ArrayList<>();
            Map<ExcelColumn, Integer> cols = header(sheet.getRow(sheet.getFirstRowNum()), notes);
            List<RawRow> rows = new ArrayList<>();
            Set<String> codes = new HashSet<>();
            for (int r = sheet.getFirstRowNum() + 1; r <= sheet.getLastRowNum(); r++) {
                Row row = sheet.getRow(r);
                if (row == null || blankRow(row)) {
                    continue;
                }
                if (rows.size() >= MAX_ROWS) {
                    notes.add("한 번에 " + MAX_ROWS + "행까지만 처리합니다. 나머지는 나눠서 올리세요");
                    break;
                }
                rows.add(row(row, cols, codes));
            }
            attachMeasurements(wb.getSheet(ExcelTemplate.MEASURE_SHEET), rows, notes);
            return new Parsed(rows, notes);
        } catch (IOException | org.apache.poi.EmptyFileException | org.apache.poi.UnsupportedFileFormatException e) {
            throw new IllegalArgumentException("엑셀 파일(.xlsx)을 읽을 수 없습니다");
        }
    }

    private static Map<ExcelColumn, Integer> header(Row h, List<String> notes) {
        if (h == null) {
            throw new IllegalArgumentException("헤더 행이 없습니다");
        }
        Map<ExcelColumn, Integer> cols = new EnumMap<>(ExcelColumn.class);
        for (Cell c : h) {
            String text = FMT.formatCellValue(c);
            ExcelColumn.byHeader(text).ifPresentOrElse(col -> cols.putIfAbsent(col, c.getColumnIndex()),
                    () -> {
                        if (!text.isBlank()) {
                            notes.add("알 수 없는 컬럼은 무시합니다: " + text);
                        }
                    });
        }
        List<String> missing = Arrays.stream(ExcelColumn.values())
                .filter(c -> c.required() && !cols.containsKey(c)).map(ExcelColumn::header).toList();
        if (!missing.isEmpty()) {
            throw new IllegalArgumentException("필수 컬럼이 없습니다: " + String.join(", ", missing));
        }
        return cols;
    }

    private static RawRow row(Row row, Map<ExcelColumn, Integer> cols, Set<String> codes) {
        List<String> errors = new ArrayList<>();
        Map<ExcelColumn, String> v = new EnumMap<>(ExcelColumn.class);
        cols.forEach((col, idx) -> v.put(col, text(row.getCell(idx))));
        for (ExcelColumn c : ExcelColumn.values()) {
            if (c.required() && v.getOrDefault(c, "").isEmpty()) {
                errors.add(c.header() + " 이(가) 비어 있습니다");
            }
        }
        String code = v.getOrDefault(ExcelColumn.CODE, "");
        if (!code.isEmpty() && !code.matches("^[A-Za-z0-9-]{1,40}$")) {
            errors.add("상품코드는 영문·숫자·하이픈 40자 이하여야 합니다: " + code);
        }
        if (!code.isEmpty() && !codes.add(code)) {
            errors.add("파일 안에서 상품코드가 중복됩니다: " + code);
        }
        Integer price = integer(v.get(ExcelColumn.SALE_PRICE), "판매가", errors);
        if (price != null && price <= 0) {
            errors.add("판매가는 0보다 커야 합니다");
        }
        Integer stock = integer(v.get(ExcelColumn.STOCK), "재고", errors);
        if (stock != null && stock < 0) {
            errors.add("재고는 0 이상이어야 합니다");
        }
        ProductRequest req = new ProductRequest(code, nz(v.get(ExcelColumn.CATEGORY)), nz(v.get(ExcelColumn.NAME)),
                nz(v.get(ExcelColumn.DESCRIPTION)), list(v.get(ExcelColumn.KEYWORDS)), price,
                nz(v.get(ExcelColumn.MATERIAL)), nz(v.get(ExcelColumn.ORIGIN_COUNTRY)),
                nz(v.get(ExcelColumn.MANUFACTURER)), nz(v.get(ExcelColumn.WASH_CARE)),
                nz(v.get(ExcelColumn.KC_CERTIFICATION)), nz(v.get(ExcelColumn.MANUFACTURED_YM)),
                nz(v.get(ExcelColumn.QUALITY_ASSURANCE)), nz(v.get(ExcelColumn.AS_MANAGER)),
                nz(v.get(ExcelColumn.AS_PHONE)));
        if (req.name() != null && req.name().length() > 200) {
            errors.add("상품명이 너무 깁니다");
        }
        return new RawRow(row.getRowNum() + 1, req, list(v.get(ExcelColumn.COLORS)), list(v.get(ExcelColumn.SIZES)),
                stock == null ? 0 : stock, new LinkedHashMap<>(), errors);
    }

    /** 실측 시트: 상품코드 | 사이즈 | 부위1 | 부위2 ... 빈 칸은 건너뛴다 */
    private static void attachMeasurements(Sheet sheet, List<RawRow> rows, List<String> notes) {
        if (sheet == null) {
            return;
        }
        Row h = sheet.getRow(sheet.getFirstRowNum());
        if (h == null) {
            return;
        }
        Map<Integer, String> parts = new HashMap<>();
        for (Cell c : h) {
            if (c.getColumnIndex() >= 2 && !text(c).isEmpty()) {
                parts.put(c.getColumnIndex(), text(c));
            }
        }
        Map<String, RawRow> byCode = new HashMap<>();
        rows.forEach(r -> byCode.putIfAbsent(r.product().code(), r));
        for (int r = sheet.getFirstRowNum() + 1; r <= sheet.getLastRowNum(); r++) {
            Row row = sheet.getRow(r);
            if (row == null || blankRow(row)) {
                continue;
            }
            final int rowNo = r + 1;
            String code = text(row.getCell(0));
            String size = text(row.getCell(1));
            RawRow target = byCode.get(code);
            if (target == null) {
                notes.add("실측 " + rowNo + "행: 상품 시트에 없는 상품코드라 건너뜁니다 (" + code + ")");
                continue;
            }
            if (size.isEmpty()) {
                target.errors().add("실측 " + rowNo + "행: 사이즈가 비어 있습니다");
                continue;
            }
            Map<String, BigDecimal> measures = target.measurements().computeIfAbsent(size, k -> new LinkedHashMap<>());
            parts.forEach((idx, part) -> {
                String raw = text(row.getCell(idx));
                if (raw.isEmpty()) {
                    return;
                }
                try {
                    measures.put(part, new BigDecimal(raw.replace("cm", "").trim()));
                } catch (NumberFormatException e) {
                    target.errors().add("실측 " + rowNo + "행 " + part + ": 숫자가 아닙니다 (" + raw + ")");
                }
            });
        }
    }

    private static Integer integer(String raw, String label, List<String> errors) {
        if (raw == null || raw.isEmpty()) {
            return null;
        }
        String cleaned = raw.replace(",", "").replace("원", "").replace("개", "").trim();
        try {
            return new BigDecimal(cleaned).intValueExact();
        } catch (NumberFormatException | ArithmeticException e) {
            errors.add(label + " 은(는) 정수여야 합니다: " + raw);
            return null;
        }
    }

    private static List<String> list(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        return Arrays.stream(raw.split("[,，\\n]")).map(String::trim).filter(s -> !s.isEmpty()).distinct().toList();
    }

    private static String text(Cell c) {
        return c == null ? "" : FMT.formatCellValue(c).trim();
    }

    private static String nz(String s) {
        return s == null || s.isEmpty() ? null : s;
    }

    private static boolean blankRow(Row row) {
        for (Cell c : row) {
            if (!text(c).isEmpty()) {
                return false;
            }
        }
        return true;
    }
}
