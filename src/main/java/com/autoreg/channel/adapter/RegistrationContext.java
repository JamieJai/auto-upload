package com.autoreg.channel.adapter;

import java.util.Map;
import java.util.UUID;

import com.autoreg.channel.ChannelAccount;
import com.autoreg.product.Product;
import com.autoreg.tenant.Tenant;

/** 어댑터가 받는 등록 재료. credentials 는 복호화된 값이라 로그에 남기지 않는다 */
public record RegistrationContext(
        Tenant tenant,
        Product product,
        ChannelAccount account,
        Map<String, String> credentials,
        String channelCategoryId,
        /** 품목 레퍼런스 스냅샷 (없으면 null) */
        Map<String, Object> reference,
        UUID idempotencyKey) {

    @Override
    public String toString() {
        return "RegistrationContext[tenant=" + tenant.getCode() + ", product=" + product.getCode() + ", channel="
                + account.getChannel() + "]";
    }
}
