package com.autoreg.webimage;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import javax.imageio.ImageIO;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.autoreg.common.ConflictException;
import com.autoreg.common.NotFoundException;
import com.autoreg.image.ImageDtos.Matched;
import com.autoreg.image.ImageService;
import com.autoreg.product.ImageSlot;
import com.autoreg.product.Product;
import com.autoreg.product.ProductImage;
import com.autoreg.product.ProductImage.SourceType;
import com.autoreg.product.ProductRepository;
import com.autoreg.tenant.Tenant;
import com.autoreg.tenant.TenantService;

import lombok.RequiredArgsConstructor;

/**
 * 웹에서 이미지 가져오기. 페이지 전체를 자동으로 긁어 등록하지 않는다:
 * 1) 후보를 보여 주고 2) 사람이 고른 것만 원본으로 받아 저장한다.
 * 페이지 도메인은 판매자의 허용 목록에 있어야 한다 (사용권 확인된 도매처만).
 */
@Service
@RequiredArgsConstructor
public class WebImageService {

    public static final int MIN_LONG_SIDE = 500;
    static final int MAX_CANDIDATES = 60;
    static final long MAX_PAGE = 5L * 1024 * 1024;
    static final long MAX_IMAGE = 25L * 1024 * 1024;

    private final SafeFetcher fetcher;
    private final TenantService tenants;
    private final ProductRepository products;
    private final ImageService images;

    public record Candidate(String url, int width, int height) {}

    public record Pick(String url, String slot) {}

    public List<Candidate> candidates(Long tenantId, String pageUrl) {
        Tenant tenant = tenants.get(tenantId);
        URI page = parse(pageUrl);
        requireAllowed(tenant, page);
        SafeFetcher.Fetched html = fetcher.get(page, MAX_PAGE, "text/html,application/xhtml+xml");
        List<URI> uris = CandidateExtractor.extract(new String(html.body(), charset(html.contentType())), html.finalUri(), MAX_CANDIDATES);
        // 크기를 알아야 작은 아이콘·배너를 거를 수 있어 받아 본다. 중복은 내용 해시로 거른다
        ExecutorService pool = Executors.newFixedThreadPool(6);
        try {
            List<Future<Object[]>> futures = new ArrayList<>();
            for (URI u : uris) {
                futures.add(pool.submit(() -> probe(u)));
            }
            List<Candidate> out = new ArrayList<>();
            Set<Integer> seen = new HashSet<>();
            for (Future<Object[]> f : futures) {
                try {
                    Object[] r = f.get(30, TimeUnit.SECONDS);
                    if (r != null && seen.add((Integer) r[3])) {
                        out.add(new Candidate((String) r[0], (Integer) r[1], (Integer) r[2]));
                    }
                } catch (Exception ignored) {
                    // 못 받은 후보는 빼고 보여 준다
                }
            }
            return out;
        } finally {
            pool.shutdownNow();
        }
    }

    /** 고른 이미지를 원본으로 받아 슬롯 끝에 붙인다. 출처 URL 을 남긴다 */
    @Transactional
    public List<Matched> importPicks(Long tenantId, Long productId, String pageUrl, List<Pick> picks) {
        Tenant tenant = tenants.get(tenantId);
        requireAllowed(tenant, parse(pageUrl));
        Product product = products.findByIdAndTenantId(productId, tenantId).orElseThrow(() -> new NotFoundException("product", productId));
        if (!product.getStatus().editable()) {
            throw new ConflictException("현재 상태(" + product.getStatus() + ")에서는 이미지를 바꿀 수 없습니다");
        }
        List<Matched> out = new ArrayList<>();
        for (Pick p : picks) {
            ImageSlot slot = ImageSlot.of(p.slot()).orElseThrow(() -> new IllegalArgumentException("알 수 없는 슬롯: " + p.slot()));
            SafeFetcher.Fetched img = fetcher.get(parse(p.url()), MAX_IMAGE, "image/*");
            int seq = product.getImages().stream().filter(i -> i.getSlot() == slot).mapToInt(ProductImage::getSeq).max().orElse(0) + 1;
            ProductImage saved = images.attach(tenant, product, slot, seq, extension(img), img.body(), SourceType.WEB, p.url());
            out.add(new Matched(p.url(), product.getId(), product.getCode(), slot.value(), seq, saved.getId()));
        }
        return out;
    }

    private Object[] probe(URI u) {
        SafeFetcher.Fetched f = fetcher.get(u, MAX_IMAGE, "image/*");
        BufferedImage img;
        try {
            img = ImageIO.read(new ByteArrayInputStream(f.body()));
        } catch (IOException e) {
            return null;
        }
        if (img == null || Math.max(img.getWidth(), img.getHeight()) < MIN_LONG_SIDE) {
            return null;
        }
        return new Object[] {u.toString(), img.getWidth(), img.getHeight(), java.util.Arrays.hashCode(f.body())};
    }

    /** 페이지 호스트가 허용 도메인이거나 그 하위 도메인이어야 한다 */
    static void requireAllowed(Tenant t, URI page) {
        String host = page.getHost().toLowerCase(Locale.ROOT);
        boolean ok = t.getAllowedImageDomains().stream().map(d -> d.toLowerCase(Locale.ROOT).replaceFirst("^\\*\\.", ""))
                .anyMatch(d -> host.equals(d) || host.endsWith("." + d));
        if (!ok) {
            throw new IllegalArgumentException(host + " 는 이 판매자의 허용 도메인이 아닙니다. 판매자 관리에서 사용권을 확인한 뒤 추가하세요");
        }
    }

    private static URI parse(String url) {
        try {
            URI u = URI.create(url == null ? "" : url.trim());
            if (u.getHost() == null) {
                throw new IllegalArgumentException();
            }
            return u;
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("주소가 올바르지 않습니다: " + url);
        }
    }

    private static String extension(SafeFetcher.Fetched f) {
        String ct = f.contentType().toLowerCase(Locale.ROOT);
        if (ct.contains("png")) {
            return "png";
        }
        if (ct.contains("webp")) {
            return "webp";
        }
        return "jpg";
    }

    private static java.nio.charset.Charset charset(String contentType) {
        int i = contentType.toLowerCase(Locale.ROOT).indexOf("charset=");
        if (i >= 0) {
            try {
                return java.nio.charset.Charset.forName(contentType.substring(i + 8).replace("\"", "").trim());
            } catch (Exception ignored) {
                // 모르는 인코딩은 UTF-8 로 본다
            }
        }
        return StandardCharsets.UTF_8;
    }
}
