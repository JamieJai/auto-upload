package com.autoreg.channel;

import java.time.OffsetDateTime;
import java.util.Map;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public final class ChannelDtos {

    private ChannelDtos() {}

    /** credentials 가 null 이면 기존 값을 유지한다 (수정 시 키를 다시 입력하지 않아도 되게) */
    public record ChannelAccountRequest(
            @NotNull Channel channel,
            @NotBlank @Size(max = 100) String displayName,
            Map<String, String> credentials,
            Boolean active) {}

    public record ChannelAccountResponse(Long id, Channel channel, String displayName, boolean hasCredentials,
            boolean active, OffsetDateTime updatedAt) {
        static ChannelAccountResponse of(ChannelAccount a) {
            return new ChannelAccountResponse(a.getId(), a.getChannel(), a.getDisplayName(),
                    a.getCredentialsEnc() != null, a.isActive(), a.getUpdatedAt());
        }
    }

    public record CategoryMappingRequest(
            @NotNull Channel channel,
            @NotBlank @Size(max = 100) String category,
            @NotBlank @Size(max = 50) String channelCategoryId) {}

    public record CategoryMappingResponse(Long id, Channel channel, String category, String channelCategoryId) {
        static CategoryMappingResponse of(CategoryMapping m) {
            return new CategoryMappingResponse(m.getId(), m.getChannel(), m.getCategory(), m.getChannelCategoryId());
        }
    }
}
