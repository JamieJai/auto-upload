package com.autoreg.excel;

import static com.autoreg.excel.ExcelTestFiles.filled;
import static com.autoreg.excel.ExcelTestFiles.product;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;

class ExcelParserTest {

    @Test
    void parsesRowsOptionsAndMeasurements() throws Exception {
        byte[] file = filled(
                new Object[][] {product("SS1", 39000, "블랙, 아이보리", "S,M", 10, "린넨 100%")},
                new Object[][] {{"SS1", "S", 100, 48.5}, {"SS1", "M", 102, ""}, {"NOPE", "S", 1}});
        var parsed = ExcelParser.parse(file);
        assertThat(parsed.rows()).hasSize(1);
        var row = parsed.rows().get(0);
        assertThat(row.errors()).isEmpty();
        assertThat(row.rowNumber()).isEqualTo(2);
        assertThat(row.product().salePrice()).isEqualTo(39000);
        assertThat(row.colors()).containsExactly("블랙", "아이보리");
        assertThat(row.sizes()).containsExactly("S", "M");
        assertThat(row.product().searchKeywords()).containsExactly("린넨", "원피스");
        assertThat(row.measurements().get("S")).containsEntry("총장", new BigDecimal("100")).containsEntry("어깨너비", new BigDecimal("48.5"));
        assertThat(row.measurements().get("M")).containsOnlyKeys("총장");
        assertThat(parsed.notes()).anyMatch(n -> n.contains("NOPE"));
    }

    @Test
    void collectsRowErrorsWithoutThrowing() throws Exception {
        byte[] file = filled(new Object[][] {
                product("SS1", "삼만원", "블랙", "S", 1, "면"),
                product("SS1", 1000, "블랙", "S", -1, "면"),
                product("bad code", 1000, "", "S", 1, null)}, new Object[0][]);
        var rows = ExcelParser.parse(file).rows();
        assertThat(rows.get(0).errors()).anyMatch(e -> e.contains("판매가"));
        assertThat(rows.get(1).errors()).anyMatch(e -> e.contains("중복")).anyMatch(e -> e.contains("재고"));
        assertThat(rows.get(2).errors()).anyMatch(e -> e.contains("색상")).anyMatch(e -> e.contains("소재"))
                .anyMatch(e -> e.contains("영문"));
    }

    @Test
    void rejectsNonExcel() {
        assertThatThrownBy(() -> ExcelParser.parse("hello".getBytes())).isInstanceOf(IllegalArgumentException.class);
    }
}
