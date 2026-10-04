package com.autoreg.image;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.autoreg.product.ImageSlot;

class ImageFilenameTest {

    @Test
    void parsesSpecExamples() {
        assertThat(ImageFilename.parse("SS2609001_main.jpg"))
                .contains(new ImageFilename("SS2609001", ImageSlot.MAIN, 1, "jpg"));
        assertThat(ImageFilename.parse("SS2609001_detail_01.jpg"))
                .contains(new ImageFilename("SS2609001", ImageSlot.DETAIL, 1, "jpg"));
        assertThat(ImageFilename.parse("SS2609001_size.JPEG"))
                .contains(new ImageFilename("SS2609001", ImageSlot.SIZE, 1, "jpg"));
    }

    @Test
    void stripsClientPathAndBuildsStoredName() {
        var f = ImageFilename.parse("C:\\Users\\me\\SS2609001_sub_12.png").orElseThrow();
        assertThat(f.storedName()).isEqualTo("sub_12.png");
    }

    @ParameterizedTest
    @ValueSource(strings = {"SS2609001.jpg", "SS2609001_front.jpg", "SS2609001_main_00.jpg", "SS2609001_main.gif",
            "SS 2609001_main.jpg", "_main.jpg", "SS2609001_main_1234.jpg"})
    void rejectsOffRuleNames(String name) {
        assertThat(ImageFilename.parse(name)).isEmpty();
    }
}
