package com.autoreg.intake;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.autoreg.tenant.PriceRule;

import tools.jackson.databind.json.JsonMapper;

class IntakeUnitTest {

    @Test
    void priceRule() {
        Map<String, Object> rule = Map.of("multiplier", 2.0, "roundUnit", 1000.0, "subtract", 100.0);
        assertThat(PriceRule.salePrice(18000, rule)).isEqualTo(35900);
        assertThat(PriceRule.salePrice(18300, rule)).isEqualTo(36900);
        assertThat(PriceRule.salePrice(18000, null)).isNull();
        assertThat(PriceRule.salePrice(null, rule)).isNull();
        assertThat(PriceRule.defaultStock(Map.of("defaultStock", 30.0))).isEqualTo(30);
        assertThat(PriceRule.defaultStock(null)).isEqualTo(10);
    }

    @Test
    void dropsValuesNotInSource() {
        String source = "도매가 18,000원 / 소재: 면 100% / 컬러: 블랙, 아이보리 / FREE / 총장 108 가슴단면 52.5";
        var out = JsonMapper.builder().build().readTree("""
                {"wholesalePrice":18000,"colors":["블랙","아이보리"],"sizes":["FREE"],"material":"면 95%, 스판 5%",
                 "originCountry":null,"washCare":null,"category":"원피스","nameHint":null,
                 "measurements":[{"size":"FREE","parts":[{"part":"총장","cm":108},{"part":"가슴단면","cm":52.5},{"part":"소매","cm":61}]}]}""");
        var ex = SourceExtractor.verify(out, source, List.of("원피스", "블라우스"));
        assertThat(ex.wholesalePrice()).isEqualTo(18000);
        assertThat(ex.material()).isNull(); // 95 가 원문에 없음
        assertThat(ex.measurements().get("FREE")).containsOnlyKeys("총장", "가슴단면")
                .containsEntry("가슴단면", new BigDecimal("52.5"));
        assertThat(ex.category()).isEqualTo("원피스");
        assertThat(ex.dropped()).anyMatch(d -> d.contains("소재")).anyMatch(d -> d.contains("소매 61"));
    }

    @Test
    void numbersAreNormalized() {
        assertThat(SourceExtractor.numbersIn("18,000원 48.50cm 07")).contains("18000", "48.5", "7");
    }
}
