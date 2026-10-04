package com.autoreg.product.validation;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.autoreg.product.ImageSlot;
import com.autoreg.product.OptionCombiner;
import com.autoreg.product.Product;
import com.autoreg.product.ProductImage;
import com.autoreg.product.ProductMeasurement;

public class ProductValidatorTest {

    private final ProductValidator validator = new ProductValidator();

    @Test
    void completeProductPassesInputPhase() {
        assertThat(validator.validate(complete(), ValidationPhase.INPUT)).isEmpty();
    }

    @Test
    void textsAreOnlyRequiredWhenReady() {
        Product p = complete();
        p.setName(null);
        p.setDescription(null);
        assertThat(validator.validate(p, ValidationPhase.INPUT)).isEmpty();
        assertThat(codes(validator.validate(p, ValidationPhase.READY))).containsExactlyInAnyOrder("name", "description");
    }

    @Test
    void zeroDetailImagesBlocksRegistration() {
        Product p = complete();
        p.getImages().removeIf(i -> i.getSlot() == ImageSlot.DETAIL);
        assertThat(validator.validate(p, ValidationPhase.INPUT))
                .extracting(ValidationIssue::field, ValidationIssue::code)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("images.detail", "IMAGE_SLOT_SHORT"));
    }

    @Test
    void everyOptionSizeNeedsMeasurement() {
        Product p = complete();
        p.getMeasurements().removeIf(m -> m.getSize().equals("L"));
        assertThat(codes(validator.validate(p, ValidationPhase.INPUT))).containsExactly("measurements.L");
    }

    @Test
    void missingNoticeAndPriceAreReported() {
        Product p = complete();
        p.setManufacturer(" ");
        p.setSalePrice(null);
        p.setManufacturedYm(null); // 선택 항목
        assertThat(codes(validator.validate(p, ValidationPhase.INPUT)))
                .containsExactlyInAnyOrder("notice.manufacturer", "salePrice");
    }

    @Test
    void asPhoneMustBeAPhoneNumber() {
        Product p = complete();
        p.setAsPhone("상세설명참조");
        assertThat(validator.validate(p, ValidationPhase.INPUT)).extracting(ValidationIssue::code).containsExactly("INVALID");
        p.setAsPhone("010-3350-8536");
        assertThat(validator.validate(p, ValidationPhase.INPUT)).isEmpty();
    }

    @Test
    void noOptionsIsReported() {
        Product p = complete();
        p.getOptions().clear();
        p.getMeasurements().clear();
        assertThat(codes(validator.validate(p, ValidationPhase.INPUT))).containsExactly("options");
    }

    @Test
    void tooLongNameFailsReady() {
        Product p = complete();
        p.setName("가".repeat(ProductValidator.NAME_MAX + 1));
        assertThat(validator.validate(p, ValidationPhase.READY)).extracting(ValidationIssue::code).containsExactly("TOO_LONG");
    }

    private static List<String> codes(List<ValidationIssue> issues) {
        return issues.stream().map(ValidationIssue::field).toList();
    }

    public static Product complete() {
        Product p = new Product();
        p.setCode("SS2609001");
        p.setCategory("원피스");
        p.setSalePrice(39000);
        p.setName("린넨 셔츠 원피스");
        p.setDescription("가볍고 시원한 린넨 원피스");
        p.setMaterial("린넨 55%, 레이온 45%");
        p.setManufacturer("(주)테스트");
        p.setOriginCountry("대한민국");
        p.setWashCare("드라이클리닝");
        p.setQualityAssurance("소비자분쟁해결기준에 따름");
        p.setAsManager("고객센터");
        p.setAsPhone("02-000-0000");
        p.replaceOptions(OptionCombiner.combine("SS2609001", List.of("블랙", "아이보리"), List.of("M", "L"), 5));
        for (String size : List.of("M", "L")) {
            ProductMeasurement m = new ProductMeasurement();
            m.setSize(size);
            m.setMeasures(Map.of("총장", new BigDecimal("110"), "가슴단면", new BigDecimal("50")));
            p.getMeasurements().add(m);
        }
        for (ImageSlot slot : ImageSlot.values()) {
            for (int seq = 1; seq <= slot.minCount(); seq++) {
                ProductImage i = new ProductImage();
                i.setSlot(slot);
                i.setSeq(seq);
                p.addImage(i);
            }
        }
        return p;
    }
}
