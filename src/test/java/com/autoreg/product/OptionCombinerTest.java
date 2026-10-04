package com.autoreg.product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.Test;

class OptionCombinerTest {

    @Test
    void expandsColorMajorAndDropsDuplicates() {
        var options = OptionCombiner.combine("SS2609001", List.of("블랙", " 아이보리 ", "블랙", ""), List.of("S", "M"), 3);
        assertThat(options).extracting(ProductOption::getColor, ProductOption::getSize)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("블랙", "S"),
                        org.assertj.core.groups.Tuple.tuple("블랙", "M"),
                        org.assertj.core.groups.Tuple.tuple("아이보리", "S"),
                        org.assertj.core.groups.Tuple.tuple("아이보리", "M"));
        assertThat(options).extracting(ProductOption::getSku)
                .containsExactly("SS2609001-01-S", "SS2609001-01-M", "SS2609001-02-S", "SS2609001-02-M");
        assertThat(options).extracting(ProductOption::getSortOrder).containsExactly(0, 1, 2, 3);
        assertThat(options).allMatch(o -> o.getStock() == 3);
    }

    @Test
    void rejectsEmptyAxis() {
        assertThatThrownBy(() -> OptionCombiner.combine("X", List.of("블랙"), List.of(" "), 0))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
