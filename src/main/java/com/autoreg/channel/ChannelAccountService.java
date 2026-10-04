package com.autoreg.channel;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.autoreg.channel.ChannelDtos.CategoryMappingRequest;
import com.autoreg.channel.ChannelDtos.ChannelAccountRequest;
import com.autoreg.common.ConflictException;
import com.autoreg.common.NotFoundException;
import com.autoreg.tenant.TenantService;

import lombok.RequiredArgsConstructor;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ChannelAccountService {

    private static final TypeReference<Map<String, String>> MAP = new TypeReference<>() {};

    private final ChannelAccountRepository accounts;
    private final CategoryMappingRepository mappings;
    private final TenantService tenants;
    private final CredentialCipher cipher;
    private final JsonMapper json;

    public List<ChannelAccount> list(Long tenantId) {
        tenants.get(tenantId);
        return accounts.findByTenantIdOrderByChannelAsc(tenantId);
    }

    public ChannelAccount get(Long tenantId, Long id) {
        return accounts.findByIdAndTenantId(id, tenantId).orElseThrow(() -> new NotFoundException("channel account", id));
    }

    @Transactional
    public ChannelAccount create(Long tenantId, ChannelAccountRequest req) {
        tenants.get(tenantId);
        if (accounts.existsByTenantIdAndChannel(tenantId, req.channel())) {
            throw new ConflictException("이 판매자에는 이미 " + req.channel() + " 계정이 있습니다");
        }
        ChannelAccount a = new ChannelAccount();
        a.setTenantId(tenantId);
        a.setChannel(req.channel());
        apply(a, req);
        return accounts.save(a);
    }

    @Transactional
    public ChannelAccount update(Long tenantId, Long id, ChannelAccountRequest req) {
        ChannelAccount a = get(tenantId, id);
        if (a.getChannel() != req.channel()) {
            throw new ConflictException("채널 종류는 바꿀 수 없습니다");
        }
        apply(a, req);
        return a;
    }

    /** 어댑터 전용. 복호화한 인증정보는 로그·응답에 남기지 않는다 */
    public Map<String, String> credentials(ChannelAccount a) {
        if (a.getCredentialsEnc() == null) {
            return Map.of();
        }
        return json.readValue(new String(cipher.decrypt(a.getCredentialsEnc()), StandardCharsets.UTF_8), MAP);
    }

    public List<CategoryMapping> mappings(Long tenantId) {
        tenants.get(tenantId);
        return mappings.findByTenantIdOrderByChannelAscCategoryAsc(tenantId);
    }

    /** 같은 (채널, 카테고리)가 있으면 덮어쓴다 */
    @Transactional
    public CategoryMapping putMapping(Long tenantId, CategoryMappingRequest req) {
        tenants.get(tenantId);
        String category = req.category().trim();
        CategoryMapping m = mappings.findByTenantIdAndChannelAndCategory(tenantId, req.channel(), category)
                .orElseGet(() -> {
                    CategoryMapping n = new CategoryMapping();
                    n.setTenantId(tenantId);
                    n.setChannel(req.channel());
                    n.setCategory(category);
                    return n;
                });
        m.setChannelCategoryId(req.channelCategoryId().trim());
        return mappings.save(m);
    }

    @Transactional
    public void deleteMapping(Long tenantId, Long id) {
        mappings.delete(mappings.findByIdAndTenantId(id, tenantId)
                .orElseThrow(() -> new NotFoundException("category mapping", id)));
    }

    private void apply(ChannelAccount a, ChannelAccountRequest req) {
        a.setDisplayName(req.displayName().trim());
        if (req.credentials() != null) {
            a.setCredentialsEnc(req.credentials().isEmpty() ? null
                    : cipher.encrypt(json.writeValueAsString(req.credentials()).getBytes(StandardCharsets.UTF_8)));
        }
        if (req.active() != null) {
            a.setActive(req.active());
        }
    }
}
