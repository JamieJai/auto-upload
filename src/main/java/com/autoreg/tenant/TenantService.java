package com.autoreg.tenant;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.autoreg.common.ConflictException;
import com.autoreg.common.NotFoundException;
import com.autoreg.product.NoticeField;
import com.autoreg.tenant.TenantDtos.TenantRequest;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class TenantService {

    private final TenantRepository tenants;

    public List<Tenant> list() {
        return tenants.findAllByOrderByNameAsc();
    }

    public Tenant get(Long id) {
        return tenants.findById(id).orElseThrow(() -> new NotFoundException("tenant", id));
    }

    @Transactional
    public Tenant create(TenantRequest req) {
        if (tenants.existsByCode(req.code())) {
            throw new ConflictException("이미 사용 중인 판매자 코드입니다: " + req.code());
        }
        Tenant t = new Tenant();
        t.setCode(req.code());
        apply(t, req);
        return tenants.save(t);
    }

    @Transactional
    public Tenant update(Long id, TenantRequest req) {
        Tenant t = get(id);
        // 코드는 이미지 폴더명이라 바꾸지 않는다
        if (!t.getCode().equals(req.code())) {
            throw new ConflictException("판매자 코드는 변경할 수 없습니다");
        }
        apply(t, req);
        return t;
    }

    private static void apply(Tenant t, TenantRequest req) {
        t.setName(req.name());
        t.setProductCodePrefix(req.productCodePrefix());
        t.setBrandTone(req.brandTone());
        t.setNoticeDefaults(checkedNoticeDefaults(req.noticeDefaults()));
        t.setAllowedImageDomains(req.allowedImageDomains() == null ? new ArrayList<>()
                : req.allowedImageDomains().stream().map(String::trim).map(String::toLowerCase)
                        .filter(s -> !s.isEmpty()).distinct().toList());
        t.setPriceRule(req.priceRule() == null || req.priceRule().isEmpty() ? null : PriceRule.check(req.priceRule()));
        if (req.active() != null) {
            t.setActive(req.active());
        }
    }

    private static Map<String, String> checkedNoticeDefaults(Map<String, String> in) {
        Map<String, String> out = new HashMap<>();
        if (in == null) {
            return out;
        }
        in.forEach((k, v) -> {
            if (NoticeField.byKey(k).isEmpty()) {
                throw new IllegalArgumentException("알 수 없는 고시정보 항목: " + k);
            }
            if (v != null && !v.isBlank()) {
                out.put(k, v.trim());
            }
        });
        return out;
    }
}
