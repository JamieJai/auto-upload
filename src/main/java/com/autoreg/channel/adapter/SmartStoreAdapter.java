package com.autoreg.channel.adapter;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.stereotype.Component;

import com.autoreg.channel.Channel;
import com.autoreg.channel.adapter.naver.NaverCommerceClient;
import com.autoreg.channel.adapter.naver.NaverCommerceClient.Credentials;
import com.autoreg.channel.adapter.naver.SmartStorePayload;
import com.autoreg.product.ProductImage;

import lombok.RequiredArgsConstructor;

/**
 * 네이버 스마트스토어(커머스API, SELF 토큰). 판매자마다 커머스API센터 애플리케이션의 clientId/clientSecret 을 쓴다.
 * dryRun(기본 켜짐)이면 등록 호출 직전에 '수정 필요'로 멈추고 보낼 본문을 응답 원문에 남긴다.
 */
@Component
@RequiredArgsConstructor
public class SmartStoreAdapter implements ChannelAdapter {

    static final String DRY_RUN = "DRY_RUN: 실제 등록은 하지 않았습니다. 채널 응답 원문에서 보낼 본문을 확인한 뒤, 채널 계정 설정의 dryRun 을 끄고 '지금 다시 시도'를 누르세요";

    private final NaverCommerceClient client;

    @Override
    public Channel channel() {
        return Channel.SMARTSTORE;
    }

    @Override
    public Optional<String> findExisting(RegistrationContext ctx) {
        return client.findBySellerCode(Credentials.of(ctx.credentials()), ctx.product().getCode());
    }

    @Override
    public String uploadImage(RegistrationContext ctx, ProductImage image, Path file) {
        return client.uploadImage(Credentials.of(ctx.credentials()), file);
    }

    @Override
    public Result register(RegistrationContext ctx, List<UploadedImage> images) {
        Map<String, Object> settings = ctx.account().getSettings();
        Map<String, Object> payload = SmartStorePayload.build(ctx.product(), ctx.channelCategoryId(), images, settings);
        if (SmartStorePayload.dryRun(settings)) {
            throw new ChannelException(DRY_RUN, false, null, Map.of("dryRun", true, "request", payload));
        }
        Map<String, Object> res = client.registerProduct(Credentials.of(ctx.credentials()), payload);
        Object no = res.get("originProductNo");
        if (no == null) {
            throw new ChannelException("등록 응답에 상품번호가 없습니다", true, null, res);
        }
        return new Result(String.valueOf(no), res);
    }

    /** 기존 상품(원상품번호)에서 배송·원산지·브랜드 등 판매자 공통 값을 가져온다 */
    public Map<String, Object> template(Map<String, String> credentials, String originProductNo) {
        return SmartStorePayload.templateSettings(client.getOriginProduct(Credentials.of(credentials), originProductNo));
    }
}
