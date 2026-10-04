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
        UUID idempotencyKey) {

    @Override
    public String toString() {
        return "RegistrationContext[tenant=" + tenant.getCode() + ", product=" + product.getCode() + ", channel="
                + account.getChannel() + "]";
    }
}
