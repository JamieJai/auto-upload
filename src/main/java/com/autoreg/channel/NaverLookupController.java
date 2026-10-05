package com.autoreg.channel;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.autoreg.channel.adapter.ChannelException;
import com.autoreg.channel.adapter.naver.NaverCommerceClient;
import com.autoreg.channel.adapter.naver.NaverCommerceClient.Credentials;

import lombok.RequiredArgsConstructor;

/** 판매자의 스마트스토어 계정으로 하는 읽기 전용 조회. 설정 화면(카테고리 매핑, 레퍼런스, 원산지 코드)을 돕는다 */
@RestController
@RequestMapping("/api/tenants/{tenantId}/naver")
@RequiredArgsConstructor
public class NaverLookupController {

    private final ChannelAccountRepository accounts;
    private final ChannelAccountService accountService;
    private final NaverCommerceClient client;

    public record Category(String id, String name) {}

    public record StoreProduct(String originProductNo, String name, String status, String categoryId, String sellerCode) {}

    /** 스토어의 원상품 하나를 그대로 (읽기 전용, 설정 확인용) */
    @GetMapping("/origin-products/{no}")
    public Object originProduct(@PathVariable Long tenantId, @PathVariable String no) {
        if (!no.matches("\\d{5,20}")) {
            throw new IllegalArgumentException("원상품번호가 올바르지 않습니다");
        }
        return call(() -> client.getOriginProduct(creds(tenantId), no));
    }

    @GetMapping("/origin-areas")
    public Object originAreas(@PathVariable Long tenantId) {
        return call(() -> client.getJson(creds(tenantId), "/v1/product-origin-areas"));
    }

    /** q 가 들어간 리프 카테고리 (예: "여성의류>티셔츠") */
    @GetMapping("/categories")
    @SuppressWarnings("unchecked")
    public List<Category> categories(@PathVariable Long tenantId, @RequestParam String q) {
        Object all = call(() -> client.getJson(creds(tenantId), "/v1/categories?last=true"));
        String needle = q.replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
        List<Category> out = new ArrayList<>();
        for (Object o : (List<Object>) all) {
            Map<String, Object> c = (Map<String, Object>) o;
            String name = String.valueOf(c.get("wholeCategoryName"));
            if (name.replaceAll("\\s+", "").toLowerCase(Locale.ROOT).contains(needle)) {
                out.add(new Category(String.valueOf(c.get("id")), name));
            }
            if (out.size() >= 50) {
                break;
            }
        }
        return out;
    }

    /** 스토어의 최근 상품 (레퍼런스 고르기용). categoryId 를 주면 그 카테고리만 */
    @GetMapping("/products")
    @SuppressWarnings("unchecked")
    public List<StoreProduct> products(@PathVariable Long tenantId, @RequestParam(required = false) String categoryId) {
        Credentials c = creds(tenantId);
        List<StoreProduct> out = new ArrayList<>();
        for (int page = 1; page <= 3 && out.size() < 30; page++) {
            int p = page;
            Map<String, Object> res = call(() -> client.searchProducts(c, p, 100));
            List<Object> contents = (List<Object>) res.getOrDefault("contents", List.of());
            for (Object o : contents) {
                Map<String, Object> m = (Map<String, Object>) o;
                List<Object> chs = (List<Object>) m.getOrDefault("channelProducts", List.of());
                Map<String, Object> ch = chs.isEmpty() ? Map.of() : (Map<String, Object>) chs.get(0);
                String cat = String.valueOf(ch.get("categoryId"));
                if (categoryId == null || categoryId.equals(cat)) {
                    out.add(new StoreProduct(String.valueOf(m.get("originProductNo")), String.valueOf(ch.get("name")),
                            String.valueOf(ch.get("statusType")), cat, ch.get("sellerManagementCode") == null ? null : String.valueOf(ch.get("sellerManagementCode"))));
                }
            }
            if (contents.size() < 100) {
                break;
            }
        }
        return out;
    }

    private Credentials creds(Long tenantId) {
        ChannelAccount a = accounts.findByTenantIdAndChannel(tenantId, Channel.SMARTSTORE)
                .orElseThrow(() -> new IllegalArgumentException("이 판매자에 스마트스토어 계정이 없습니다"));
        return Credentials.of(accountService.credentials(a));
    }

    private static <T> T call(java.util.function.Supplier<T> s) {
        try {
            return s.get();
        } catch (ChannelException e) {
            throw new IllegalArgumentException(e.getMessage());
        }
    }
}
