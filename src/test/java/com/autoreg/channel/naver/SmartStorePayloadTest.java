package com.autoreg.channel.naver;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.autoreg.channel.adapter.ChannelAdapter.UploadedImage;
import com.autoreg.channel.adapter.ChannelException;
import com.autoreg.channel.adapter.naver.SmartStorePayload;
import com.autoreg.product.Product;
import com.autoreg.product.ProductImage;

class SmartStorePayloadTest {

    static final Map<String, Object> SETTINGS = Map.of(
            "deliveryInfo", Map.of("deliveryType", "DELIVERY"),
            "originAreaInfo", Map.of("originAreaCode", "00", "content", "국산"),
            "naverShoppingSearchInfo", Map.of("brandName", "charming_point_", "modelName", "버린다"));

    @Test
    @SuppressWarnings("unchecked")
    void mapsProductToRegistrationBody() {
        Product p = com.autoreg.product.validation.ProductValidatorTest.complete();
        p.setDescription("첫 문단 <b>\n둘째 줄\n\n두 번째 문단");
        p.setSearchKeywords(new ArrayList<>(List.of("린넨", "원피스")));
        p.getOptions().get(0).setColorDisplay("딥 블랙");

        Map<String, Object> body = SmartStorePayload.build(p, "50000807", uploaded(p), SETTINGS);
        Map<String, Object> origin = (Map<String, Object>) body.get("originProduct");
        Map<String, Object> detail = (Map<String, Object>) origin.get("detailAttribute");

        assertThat(origin).containsEntry("leafCategoryId", "50000807").containsEntry("salePrice", 39000)
                .containsEntry("stockQuantity", 20);
        assertThat((Map<String, Object>) detail.get("sellerCodeInfo")).containsEntry("sellerManagementCode", "SS2609001");
        assertThat((Map<String, Object>) detail.get("naverShoppingSearchInfo")).containsEntry("brandName", "charming_point_")
                .doesNotContainKey("modelName");

        Map<String, Object> notice = (Map<String, Object>) ((Map<String, Object>) detail.get("productInfoProvidedNotice")).get("wear");
        assertThat(notice).containsEntry("material", "린넨 55%, 레이온 45%").containsEntry("color", "딥 블랙/블랙/아이보리")
                .containsEntry("size", "M/L").containsEntry("caution", "드라이클리닝");

        List<Map<String, Object>> combos = (List<Map<String, Object>>) ((Map<String, Object>) detail.get("optionInfo")).get("optionCombinations");
        assertThat(combos).hasSize(4);
        assertThat(combos.get(0)).containsEntry("optionName1", "딥 블랙").containsEntry("optionName2", "M").containsEntry("stockQuantity", 5);

        Map<String, Object> images = (Map<String, Object>) origin.get("images");
        assertThat(((Map<String, Object>) images.get("representativeImage")).get("url")).isEqualTo("https://img/main1");
        assertThat((List<?>) images.get("optionalImages")).hasSize(2); // 연출컷 2장. 디테일·사이즈표는 상세설명에

        String html = (String) origin.get("detailContent");
        assertThat(html).contains("첫 문단 &lt;b&gt;<br>둘째 줄").contains("<p style=\"margin:0 0 18px\">두 번째 문단")
                .contains("https://img/detail1").contains("가슴단면").contains("https://img/size1");

        assertThat((Map<String, Object>) body.get("smartstoreChannelProduct"))
                .containsEntry("channelProductDisplayStatusType", "SUSPENSION");
        assertThat(SmartStorePayload.dryRun(SETTINGS)).isTrue();
        assertThat(SmartStorePayload.dryRun(Map.of("dryRun", false))).isFalse();
    }

    @Test
    void requiresTemplateSettings() {
        Product p = com.autoreg.product.validation.ProductValidatorTest.complete();
        assertThatThrownBy(() -> SmartStorePayload.build(p, "1", uploaded(p), Map.of()))
                .isInstanceOf(ChannelException.class).hasMessageContaining("deliveryInfo, originAreaInfo");
    }

    static List<UploadedImage> uploaded(Product p) {
        List<UploadedImage> out = new ArrayList<>();
        for (ProductImage i : p.getImages()) {
            out.add(new UploadedImage(i, "https://img/" + i.getSlot().value() + i.getSeq()));
        }
        return out;
    }
}
