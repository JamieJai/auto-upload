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
            Map<String, Object> settings,
            Boolean active) {}

    public record ChannelAccountResponse(Long id, Channel channel, String displayName, boolean hasCredentials,
            Map<String, Object> settings, boolean active, OffsetDateTime updatedAt) {
        static ChannelAccountResponse of(ChannelAccount a) {
            return new ChannelAccountResponse(a.getId(), a.getChannel(), a.getDisplayName(),
                    a.getCredentialsEnc() != null, a.getSettings(), a.isActive(), a.getUpdatedAt());
        }
    }

    public record TemplateRequest(@NotBlank String originProductNo) {}

    public record CategoryMappingRequest(
            @NotNull Channel channel,
            @NotBlank @Size(max = 100) String category,
            @NotBlank @Size(max = 50) String channelCategoryId) {}

    public record CategoryMappingResponse(Long id, Channel channel, String category, String channelCategoryId,
            String referenceProductNo, String referenceName, OffsetDateTime referenceFetchedAt, Map<String, Object> reference) {
        static CategoryMappingResponse of(CategoryMapping m) {
            return new CategoryMappingResponse(m.getId(), m.getChannel(), m.getCategory(), m.getChannelCategoryId(),
                    m.getReferenceProductNo(), m.getReferenceName(), m.getReferenceFetchedAt(), m.getReference());
        }
    }

    /** force: 상품명에 테스트·세일 등이 있어도 레퍼런스로 쓴다 */
    public record ReferenceRequest(@NotBlank String originProductNo, Boolean force) {}
}
