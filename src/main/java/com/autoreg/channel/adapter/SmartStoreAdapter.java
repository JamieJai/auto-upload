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
import com.autoreg.channel.adapter.naver.SmartStoreReference;
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
        Map<String, Object> payload = SmartStorePayload.build(ctx.product(), ctx.channelCategoryId(), images, settings, ctx.reference());
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

    /**
     * 재조회: 상품명·전시 상태·상세 이미지 수가 보낸 값과 같은지 본다.
     * 네이버가 상세 HTML 을 다시 쓰므로 이미지는 개수만 비교한다.
     */
    @Override
    @SuppressWarnings("unchecked")
    public Optional<String> verify(RegistrationContext ctx, String originProductNo, Map<String, Object> raw) {
        Object channelNo = raw == null ? null : raw.get("smartstoreChannelProductNo");
        if (channelNo == null) {
            return Optional.of("등록 응답에 채널상품번호가 없어 재조회하지 못했습니다");
        }
        Map<String, Object> got;
        try {
            got = client.getChannelProduct(Credentials.of(ctx.credentials()), String.valueOf(channelNo));
        } catch (ChannelException e) {
            return Optional.of("재조회 실패: " + e.getMessage());
        }
        List<String> problems = new java.util.ArrayList<>();
        Map<String, Object> origin = got.get("originProduct") instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
        Map<String, Object> channel = got.get("smartstoreChannelProduct") instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
        if (!ctx.product().getName().equals(origin.get("name"))) {
            problems.add("상품명이 다릅니다 (" + origin.get("name") + ")");
        }
        Object expected = ctx.account().getSettings().getOrDefault("displayStatus", "SUSPENSION");
        if (!String.valueOf(expected).equals(String.valueOf(channel.get("channelProductDisplayStatusType")))) {
            problems.add("전시 상태가 " + channel.get("channelProductDisplayStatusType") + " 입니다 (기대: " + expected + ")");
        }
        long expectedImgs = ctx.product().getImages().stream()
                .filter(i -> i.getSlot() == com.autoreg.product.ImageSlot.DETAIL || i.getSlot() == com.autoreg.product.ImageSlot.SIZE).count();
        int actualImgs = countImgs(String.valueOf(origin.getOrDefault("detailContent", "")));
        if (actualImgs < expectedImgs) {
            problems.add("상세 이미지가 " + actualImgs + "장입니다 (보낸 것 " + expectedImgs + "장)");
        }
        return problems.isEmpty() ? Optional.empty() : Optional.of(String.join("; ", problems));
    }

    static int countImgs(String html) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("<img\\b", java.util.regex.Pattern.CASE_INSENSITIVE).matcher(html);
        int n = 0;
        while (m.find()) {
            n++;
        }
        return n;
    }

    /** 품목 레퍼런스: 기존 상품(원상품번호)을 조회해 신규 등록에 쓸 판매 설정만 남긴다 */
    public Map<String, Object> reference(Map<String, String> credentials, String originProductNo, boolean allowUnsafe) {
        return SmartStoreReference.snapshot(originProductNo,
                client.getOriginProduct(Credentials.of(credentials), originProductNo), allowUnsafe);
    }

    /** 기존 상품(원상품번호)에서 배송·원산지·브랜드 등 판매자 공통 값을 가져온다 */
    public Map<String, Object> template(Map<String, String> credentials, String originProductNo) {
        return SmartStorePayload.templateSettings(client.getOriginProduct(Credentials.of(credentials), originProductNo));
    }
}
