package com.autoreg.image;

import java.time.OffsetDateTime;
import java.util.List;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public final class ImageDtos {

    private ImageDtos() {}

    public record Matched(String filename, Long productId, String productCode, String slot, int seq, Long imageId) {}

    public record Unmatched(String filename, String reason) {}

    public record UploadResult(List<Matched> matched, List<Unmatched> unmatched) {}

    public record UnmatchedFile(String filename, long size, OffsetDateTime modifiedAt) {}

    public record AssignRequest(@NotBlank String filename, @NotNull Long productId, @NotBlank String slot,
            @Min(1) @Max(999) int seq) {}
}
