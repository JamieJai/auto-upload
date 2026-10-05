package com.autoreg.channel.naver;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.autoreg.channel.adapter.ChannelException;
import com.autoreg.channel.adapter.naver.SmartStorePayload;
import com.autoreg.channel.adapter.naver.SmartStoreReference;
import com.autoreg.product.Product;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

class SmartStoreReferenceTest {

    static final JsonMapper JSON = JsonMapper.builder().build();

    static Map<String, Object> getResponse(String name) {
        return JSON.readValue("""
                {"originProduct":{"name":"%s","leafCategoryId":"50000807","salePrice":52000,"customerBenefit":{},
                  "images":{"representativeImage":{"url":"https://old"}},
                  "deliveryInfo":{"deliveryType":"DELIVERY","deliveryBundleGroupId":14228796},
                  "detailAttribute":{"originAreaInfo":{"originAreaCode":"00"},"taxType":"TAX","sellerCommentUsable":false,
                    "itselfProductionProductYn":false,"productAttributes":[{"attributeSeq":1,"attributeValueSeq":2}],
                    "purchaseReviewInfo":{"purchaseReviewExposure":true},"afterServiceInfo":{"afterServiceTelephoneNumber":"0101"},
                    "naverShoppingSearchInfo":{"modelName":"옛모델","brandName":"b","manufacturerName":"m","modelId":9},
                    "optionInfo":{"optionCombinations":[{"id":1}]},"seoInfo":{"sellerTags":[]},"manufactureDate":"2026-08-01",
                    "productInfoProvidedNotice":{"productInfoProvidedNoticeType":"WEAR"}}},
                 "smartstoreChannelProduct":{"naverShoppingRegistration":true,"bbsSeq":3394,"channelProductDisplayStatusType":"ON"}}"""
                .formatted(name), new TypeReference<Map<String, Object>>() {});
    }

    @Test
    @SuppressWarnings("unchecked")
    void keepsSaleSettingsDropsIdsAndProductValues() {
        Map<String, Object> snap = SmartStoreReference.snapshot("13677412599", getResponse("린넨 셔츠 롱원피스"), false);
        Map<String, Object> d = (Map<String, Object>) snap.get("detailAttribute");
        assertThat(snap).containsEntry("leafCategoryId", "50000807").containsKey("deliveryInfo")
                .doesNotContainKeys("customerBenefit", "images", "salePrice", "name");
        assertThat(d).containsKeys("originAreaInfo", "productAttributes", "purchaseReviewInfo", "sellerCommentUsable",
                "itselfProductionProductYn", "taxType", "afterServiceInfo")
                .doesNotContainKeys("optionInfo", "seoInfo", "manufactureDate", "productInfoProvidedNotice");
        assertThat((Map<String, Object>) d.get("naverShoppingSearchInfo")).containsOnlyKeys("brandName", "manufacturerName");
        assertThat((Map<String, Object>) snap.get("smartstoreChannelProduct")).containsOnlyKeys("naverShoppingRegistration");
    }

    @Test
    void refusesTestOrSaleProductsUnlessForced() {
        assertThatThrownBy(() -> SmartStoreReference.snapshot("1", getResponse("[테스트] 마들렌 원피스"), false))
                .isInstanceOf(ChannelException.class).hasMessageContaining("테스트");
        assertThatThrownBy(() -> SmartStoreReference.snapshot("1", getResponse("여름 SALE 원피스"), false))
                .hasMessageContaining("sale");
        assertThat(SmartStoreReference.snapshot("1", getResponse("[테스트] 마들렌 원피스"), true)).containsKey("deliveryInfo");
    }

    @Test
    @SuppressWarnings("unchecked")
    void buildsFromReferenceWithStoreRules() {
        Map<String, Object> ref = SmartStoreReference.snapshot("1", getResponse("린넨 셔츠 롱원피스"), false);
        Product p = com.autoreg.product.validation.ProductValidatorTest.complete();
        p.setManufacturedYm("2026-10");
        p.setSearchKeywords(new ArrayList<>(List.of("린넨원피스", "아주아주긴린넨롱원피스태그")));
        Map<String, Object> settings = Map.of("optionGroupName1", "color", "optionGroupName2", "size", "lowercaseOptionValues", true);
        p.getOptions().forEach(o -> o.setSize(o.getSize().equals("M") ? "Free" : "M"));

        Map<String, Object> body = SmartStorePayload.build(p, "50000807", SmartStorePayloadTest.uploaded(p), settings, ref);
        Map<String, Object> origin = (Map<String, Object>) body.get("originProduct");
        Map<String, Object> d = (Map<String, Object>) origin.get("detailAttribute");
        assertThat(origin).doesNotContainKey("customerBenefit");
        assertThat(d).containsEntry("manufactureDate", "2026-10-01").containsKey("productAttributes");
        assertThat((Map<String, Object>) d.get("naverShoppingSearchInfo")).containsEntry("modelName", p.getName())
                .containsEntry("brandName", "b");
        Map<String, Object> opt = (Map<String, Object>) d.get("optionInfo");
        assertThat((Map<String, Object>) opt.get("optionCombinationGroupNames")).containsEntry("optionGroupName1", "color");
        assertThat((List<Map<String, Object>>) opt.get("optionCombinations")).extracting(c -> c.get("optionName2")).contains("free", "m");
        assertThat(opt).containsEntry("optionDeliveryAttributes", List.of());
        // 30바이트 이상 태그는 빠진다
        assertThat((List<Map<String, String>>) ((Map<String, Object>) d.get("seoInfo")).get("sellerTags"))
                .extracting(t -> t.get("text")).containsExactly("린넨원피스");
        assertThat((Map<String, Object>) body.get("smartstoreChannelProduct")).doesNotContainKey("bbsSeq")
                .containsEntry("channelProductDisplayStatusType", "SUSPENSION");

        assertThatThrownBy(() -> SmartStorePayload.build(p, "50000999", SmartStorePayloadTest.uploaded(p), settings, ref))
                .hasMessageContaining("레퍼런스 카테고리");
    }

    @Test
    void webpIsConvertedToJpeg() {
        byte[] webp = Base64.getDecoder().decode("UklGRnAAAABXRUJQVlA4WAoAAAAQAAAAJwAAHQAAQUxQSAoAAAABB1DAiAhERP8DVlA4IEAAAABQAwCdASooAB4APm02l0ikIyIhJWgAgA2JZwDQvoAAK/fDcAD+8DhD/raTNCmQ//2ln/1LP/qWf4wj7LOgAAAA");
        byte[] jpg = com.autoreg.channel.adapter.naver.NaverCommerceClientAccess.toJpeg(webp);
        assertThat(jpg[0] & 0xff).isEqualTo(0xFF);
        assertThat(jpg[1] & 0xff).isEqualTo(0xD8);
    }
}
