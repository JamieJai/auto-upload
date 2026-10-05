package com.autoreg.intake;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.autoreg.common.NotFoundException;
import com.autoreg.intake.IntakeService.Meta;
import com.autoreg.intake.IntakeService.Result;
import com.autoreg.tenant.TenantService;

import lombok.RequiredArgsConstructor;

@RestController
@RequiredArgsConstructor
public class IntakeController {

    private final IntakeService intake;
    private final IntakeTokenService tokens;
    private final TenantService tenants;

    public record TenantOption(Long id, String code, String name) {}

    public record TokenView(Long id, String name, OffsetDateTime createdAt, OffsetDateTime lastUsedAt, OffsetDateTime revokedAt) {}

    public record IssueRequest(String name) {}

    public record SourceView(String sourceUrl, String title, String rawText, Map<String, Object> extracted, String notes,
            OffsetDateTime capturedAt, OffsetDateTime extractedAt) {}

    // ---- 확장 (토큰 인증) ----

    @GetMapping("/api/intake/tenants")
    public List<TenantOption> tenants() {
        return tenants.list().stream().filter(t -> t.isActive()).map(t -> new TenantOption(t.getId(), t.getCode(), t.getName())).toList();
    }

    /** meta: JSON 파트 {tenantId, url, title, text, images:[{url, slot}]}, files: images 와 같은 순서 */
    @PostMapping(path = "/api/intake/products", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public Result capture(@RequestPart("meta") Meta meta, @RequestPart(name = "files", required = false) List<MultipartFile> files) {
        return intake.capture(meta, files == null ? List.of() : files);
    }

    // ---- 대시보드 (세션) ----

    @GetMapping("/api/intake-tokens")
    public List<TokenView> listTokens() {
        return tokens.list().stream().map(t -> new TokenView(t.getId(), t.getName(), t.getCreatedAt(), t.getLastUsedAt(), t.getRevokedAt())).toList();
    }

    /** 토큰 원문은 이 응답에서만 보인다 */
    @PostMapping("/api/intake-tokens")
    @ResponseStatus(HttpStatus.CREATED)
    public IntakeTokenService.Issued issue(@RequestBody(required = false) IssueRequest req) {
        return tokens.issue(req == null ? null : req.name());
    }

    @DeleteMapping("/api/intake-tokens/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void revoke(@PathVariable Long id) {
        tokens.revoke(id);
    }

    @GetMapping("/api/tenants/{tenantId}/products/{id}/source")
    public SourceView source(@PathVariable Long tenantId, @PathVariable Long id) {
        ProductSource s = intake.source(tenantId, id).orElseThrow(() -> new NotFoundException("source", id));
        return new SourceView(s.getSourceUrl(), s.getTitle(), s.getRawText(), s.getExtracted(), s.getNotes(), s.getCapturedAt(),
                s.getExtractedAt());
    }
}
