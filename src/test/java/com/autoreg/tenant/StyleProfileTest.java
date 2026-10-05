package com.autoreg.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.autoreg.channel.adapter.naver.SmartStorePayload;
import com.autoreg.channel.adapter.naver.SmartStoreReference;
import com.autoreg.product.Product;
import com.autoreg.product.validation.ProductValidator;
import com.autoreg.product.validation.ProductValidatorTest;
import com.autoreg.product.validation.ValidationIssue;
import com.autoreg.product.validation.ValidationPhase;

class StyleProfileTest {

    static StyleProfile shopB() {
        StyleProfile s = new StyleProfile();
        s.getCopy().setNamePrefix("[B] ");
        s.getCopy().setNameSuffix(" (2 color)");
        s.getCopy().setBannedWords(new ArrayList<>(List.of("최저가")));
        s.getTags().setMin(10);
        s.getTags().setMax(10);
        s.getTags().setTextCase(StyleProfile.Case.LOWER);
        s.getOptions().setGroupName1("COLOR");
        s.getOptions().setGroupName2("SIZE");
        s.getOptions().setColorCase(StyleProfile.Case.UPPER);
        s.getOptions().setSizeCase(StyleProfile.Case.UPPER);
        s.getOptions().getSizeAliases().put("FREE", "F");
        s.getImages().setOptionalOrder(new ArrayList<>(List.of(StyleProfile.ImageGroup.SUB, StyleProfile.ImageGroup.DETAIL)));
        s.getImages().setMaxOptional(3);
        s.getDetail().setBlocks(new ArrayList<>(List.of(StyleProfile.DetailBlock.SIZE_TABLE, StyleProfile.DetailBlock.TEXT,
                StyleProfile.DetailBlock.DETAIL_IMAGES)));
        s.getRegistration().setDiscountValue(5);
        return s;
    }

    @Test
    void roundTripsAndKeepsDefaultsForMissingKeys() {
        StyleProfile s = StyleProfile.from(Map.of("tags", Map.of("min", 5), "unknownKey", 1));
        assertThat(s.getTags().getMin()).isEqualTo(5);
        assertThat(s.getTags().getMax()).isEqualTo(10);
        assertThat(s.getOptions().getGroupName1()).isEqualTo("색상");
        assertThat(StyleProfile.from(shopB().toMap())).isEqualTo(shopB());
    }

    @Test
    void transforms() {
        StyleProfile s = shopB();
        assertThat(s.finalName("린넨 원피스")).isEqualTo("[B] 린넨 원피스 (2 color)");
        assertThat(s.finalName("[B] 린넨 원피스 (2 color)")).isEqualTo("[B] 린넨 원피스 (2 color)");
        assertThat(s.size("free")).isEqualTo("F");
        assertThat(s.size("m")).isEqualTo("M");
        assertThat(s.color("black")).isEqualTo("BLACK");
        assertThat(s.tag("Linen")).isEqualTo("linen");
    }

    @Test
    void rejectsOutOfRange() {
        StyleProfile s = new StyleProfile();
        s.getTags().setMin(11);
        assertThatThrownBy(s::checked).isInstanceOf(IllegalArgumentException.class);
        StyleProfile d = new StyleProfile();
        d.getRegistration().setDiscountValue(100);
        assertThatThrownBy(d::checked).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void legacyAccountSettingsMapToStyle() {
        StyleProfile s = StyleProfile.legacy(Map.of("optionGroupName1", "color", "lowercaseOptionValues", true, "displayStatus", "ON"));
        assertThat(s.getOptions().getGroupName1()).isEqualTo("color");
        assertThat(s.getOptions().getSizeCase()).isEqualTo(StyleProfile.Case.LOWER);
        assertThat(s.getRegistration().getDisplayStatus()).isEqualTo("ON");
    }

    @Test
    void validatorFollowsStyle() {
        Product p = ProductValidatorTest.complete();
        p.setSearchKeywords(new ArrayList<>(List.of("린넨", "원피스")));
        p.setDescription("오늘만 최저가 원피스");
        List<ValidationIssue> issues = new ProductValidator().validate(p, ValidationPhase.READY, shopB());
        assertThat(issues).extracting(ValidationIssue::code).containsExactlyInAnyOrder("TOO_FEW", "BANNED_WORD");

        StyleProfile shortName = new StyleProfile();
        shortName.getCopy().setNameMaxLength(15);
        shortName.getCopy().setNameSuffix(" (2 color)");
        p.setDescription("설명");
        p.setName("린넨 셔츠 원피스");
        assertThat(new ProductValidator().validate(p, ValidationPhase.READY, shortName)).extracting(ValidationIssue::code)
                .contains("TOO_LONG");
    }

    @Test
    @SuppressWarnings("unchecked")
    void payloadFollowsStyle() {
        Product p = ProductValidatorTest.complete();
        p.getOptions().forEach(o -> o.setSize(o.getSize().equals("M") ? "free" : "l"));
        p.setSearchKeywords(new ArrayList<>(List.of("Linen", "Dress")));
        Map<String, Object> settings = Map.of("deliveryInfo", Map.of("d", 1), "originAreaInfo", Map.of("originAreaCode", "00"));
        var images = new ArrayList<com.autoreg.channel.adapter.ChannelAdapter.UploadedImage>();
        p.getImages().forEach(i -> images.add(new com.autoreg.channel.adapter.ChannelAdapter.UploadedImage(i, "u/" + i.getSlot().value() + i.getSeq())));

        Map<String, Object> body = SmartStorePayload.build(p, "50000807", images, settings, SmartStoreReference.fromAccountSettings(settings), shopB());
        Map<String, Object> origin = (Map<String, Object>) body.get("originProduct");
        assertThat(origin.get("name")).isEqualTo("[B] 린넨 셔츠 원피스 (2 color)");
        assertThat(((Map<String, Object>) body.get("smartstoreChannelProduct")).get("channelProductName")).isEqualTo(origin.get("name"));
        List<Map<String, String>> optional = (List<Map<String, String>>) ((Map<String, Object>) origin.get("images")).get("optionalImages");
        assertThat(optional).extracting(m -> m.get("url")).containsExactly("u/sub1", "u/sub2", "u/detail1");
        assertThat(origin).containsKey("customerBenefit");
        Map<String, Object> d = (Map<String, Object>) origin.get("detailAttribute");
        Map<String, Object> opt = (Map<String, Object>) d.get("optionInfo");
        assertThat((Map<String, Object>) opt.get("optionCombinationGroupNames")).containsEntry("optionGroupName1", "COLOR");
        assertThat((List<Map<String, Object>>) opt.get("optionCombinations")).extracting(c -> c.get("optionName1") + "/" + c.get("optionName2"))
                .contains("블랙/F", "블랙/L");
        assertThat((List<Map<String, String>>) ((Map<String, Object>) d.get("seoInfo")).get("sellerTags"))
                .extracting(t -> t.get("text")).containsExactly("linen", "dress");
        String html = (String) origin.get("detailContent");
        assertThat(html.indexOf("<table")).isLessThan(html.indexOf("<p ")).isLessThan(html.indexOf("u/detail1"));
        assertThat(html).doesNotContain("u/size1"); // 사이즈표 이미지 블록을 빼면 상세에서도 빠진다
    }
}
