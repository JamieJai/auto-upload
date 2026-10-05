package com.autoreg.tenant;

import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;

/**
 * 특성 조회·저장·복제. 복제는 복사다: 복사 후 각자 따로 고치고, 필요하면 원본에서 다시 덮어쓴다.
 * 문체·가격 규칙·고시정보 기본값도 함께 복사하되, 스토어마다 다른 값(A/S 담당·전화, 제조사)은 남긴다.
 */
@RestController
@RequestMapping("/api/tenants/{tenantId}/style")
@RequiredArgsConstructor
public class StyleController {

    /** 스토어마다 다른 고시정보 기본값. 복제하지 않는다 */
    static final List<String> STORE_SPECIFIC_NOTICE = List.of("as_manager", "as_phone", "manufacturer");

    private final TenantService tenants;

    public record StyleView(StyleProfile style, Long sourceTenantId, String sourceTenantName, OffsetDateTime copiedAt) {}

    public record CopyRequest(@NotNull Long sourceTenantId) {}

    @GetMapping
    public StyleView get(@PathVariable Long tenantId) {
        return view(tenants.get(tenantId));
    }

    @PutMapping
    @Transactional
    public StyleView save(@PathVariable Long tenantId, @RequestBody StyleProfile style) {
        Tenant t = tenants.get(tenantId);
        t.setStyle(style.checked().toMap());
        return view(t);
    }

    @PostMapping("/copy")
    @Transactional
    public StyleView copy(@PathVariable Long tenantId, @jakarta.validation.Valid @RequestBody CopyRequest req) {
        Tenant target = tenants.get(tenantId);
        if (target.getId().equals(req.sourceTenantId())) {
            throw new IllegalArgumentException("같은 판매자에서 복사할 수 없습니다");
        }
        Tenant source = tenants.get(req.sourceTenantId());
        target.setStyle(source.styleProfile().toMap());
        target.setBrandTone(source.getBrandTone());
        target.setPriceRule(source.getPriceRule() == null ? null : new HashMap<>(source.getPriceRule()));
        Map<String, String> notice = new HashMap<>(source.getNoticeDefaults());
        STORE_SPECIFIC_NOTICE.forEach(notice::remove);
        STORE_SPECIFIC_NOTICE.forEach(k -> {
            String mine = target.getNoticeDefaults().get(k);
            if (mine != null) {
                notice.put(k, mine);
            }
        });
        target.setNoticeDefaults(notice);
        target.setStyleSourceTenantId(source.getId());
        target.setStyleCopiedAt(OffsetDateTime.now());
        return view(target);
    }

    private StyleView view(Tenant t) {
        String sourceName = t.getStyleSourceTenantId() == null ? null
                : tenants.list().stream().filter(x -> x.getId().equals(t.getStyleSourceTenantId())).map(Tenant::getName).findFirst().orElse(null);
        return new StyleView(t.styleProfile(), t.getStyleSourceTenantId(), sourceName, t.getStyleCopiedAt());
    }
}
