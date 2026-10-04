package com.autoreg.channel.adapter;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.autoreg.channel.Channel;
import com.autoreg.product.ProductImage;

/**
 * 채널별 등록 방법. 파이프라인(RegisterJobHandler)이 순서대로 호출한다:
 * findExisting → uploadImage(이미지마다, 캐시에 없을 때만) → register.
 * 모든 실패는 ChannelException 으로 던진다.
 */
public interface ChannelAdapter {

    Channel channel();

    /** 판매자 상품코드로 이미 등록된 상품이 있으면 채널 상품번호. 재시도로 인한 중복 등록을 막는다 */
    Optional<String> findExisting(RegistrationContext ctx);

    /** 이미지 1장을 채널에 올리고 채널이 준 URL 을 돌려준다 */
    String uploadImage(RegistrationContext ctx, ProductImage image, Path file);

    /** 등록. images 는 업로드 순서(main, sub, detail, size)대로 채널 URL */
    Result register(RegistrationContext ctx, List<UploadedImage> images);

    record UploadedImage(ProductImage image, String channelUrl) {}

    record Result(String channelProductNo, Map<String, Object> rawResponse) {}
}
