package com.autoreg.channel.adapter;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Component;

import com.autoreg.channel.Channel;
import com.autoreg.product.ProductImage;

/**
 * 네이버 스마트스토어(커머스API). 실제 호출은 M0 에서 테스트 스토어로 API 동작을 확인한 뒤 구현한다.
 * 그 전에는 사용자가 고쳐야 하는 실패(FAILED_INVALID)로 멈춰서, 파이프라인 나머지는 끝까지 검증할 수 있게 한다.
 */
@Component
public class SmartStoreAdapter implements ChannelAdapter {

    static final String NOT_READY = "스마트스토어 연동은 아직 구현되지 않았습니다 (M0: 테스트 스토어 API 확인 후 구현)";

    @Override
    public Channel channel() {
        return Channel.SMARTSTORE;
    }

    @Override
    public Optional<String> findExisting(RegistrationContext ctx) {
        throw ChannelException.invalid(NOT_READY);
    }

    @Override
    public String uploadImage(RegistrationContext ctx, ProductImage image, Path file) {
        throw ChannelException.invalid(NOT_READY);
    }

    @Override
    public Result register(RegistrationContext ctx, List<UploadedImage> images) {
        throw ChannelException.invalid(NOT_READY);
    }
}
