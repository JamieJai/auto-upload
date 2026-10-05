package com.autoreg.intake;

import java.io.IOException;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import com.autoreg.common.ConflictException;
import com.autoreg.common.NotFoundException;
import com.autoreg.image.ImageService;
import com.autoreg.job.JobService;
import com.autoreg.job.JobType;
import com.autoreg.product.ImageSlot;
import com.autoreg.product.Product;
import com.autoreg.product.ProductDtos.ProductRequest;
import com.autoreg.product.ProductImage;
import com.autoreg.product.ProductImage.SourceType;
import com.autoreg.product.ProductRepository;
import com.autoreg.product.ProductService;
import com.autoreg.tenant.Tenant;
import com.autoreg.tenant.TenantService;

import lombok.RequiredArgsConstructor;

/**
 * 확장이 보낸 도매처 페이지로 상품 초안을 만든다: 코드 자동 채번 → 이미지 첨부 → 원문 저장 → EXTRACT 작업.
 * 값 채우기는 워커가 한다 (요청이 LLM 을 기다리지 않게).
 */
@Service
@RequiredArgsConstructor
public class IntakeService {

    static final int MAX_TEXT = 60_000;

    private final TenantService tenants;
    private final ProductService productService;
    private final ProductRepository products;
    private final ImageService images;
    private final ProductSourceRepository sources;
    private final JobService jobs;

    public record ImageMeta(String url, String slot) {}

    public record Meta(Long tenantId, String url, String title, String text, List<ImageMeta> images) {}

    public record Result(Long productId, String code, int imageCount, List<String> warnings) {}

    @Transactional
    public Result capture(Meta meta, List<MultipartFile> files) {
        if (meta.tenantId() == null) {
            throw new IllegalArgumentException("판매자를 고르세요");
        }
        Tenant t = tenants.get(meta.tenantId());
        if (!t.isActive()) {
            throw new ConflictException("비활성 판매자입니다: " + t.getName());
        }
        String text = meta.text() == null ? "" : meta.text().strip();
        if (text.length() < 20) {
            throw new IllegalArgumentException("페이지 글이 너무 짧습니다. 상품 상세 페이지에서 보내세요");
        }
        String code = nextCode(t);
        Long id = productService.create(t.getId(), new ProductRequest(code, null, null, null, List.of(), null, null, null,
                null, null, null, null, null, null, null)).id();
        Product p = products.findById(id).orElseThrow();

        List<String> warnings = new ArrayList<>();
        List<ImageMeta> metas = meta.images() == null ? List.of() : meta.images();
        int attached = 0;
        for (int i = 0; i < (files == null ? 0 : files.size()); i++) {
            MultipartFile f = files.get(i);
            ImageMeta im = i < metas.size() ? metas.get(i) : new ImageMeta(null, "detail");
            ImageSlot slot = ImageSlot.of(im.slot()).orElse(ImageSlot.DETAIL);
            int seq = p.getImages().stream().filter(x -> x.getSlot() == slot).mapToInt(ProductImage::getSeq).max().orElse(0) + 1;
            try {
                images.attach(t, p, slot, seq, extension(f), f.getBytes(), SourceType.WEB, im.url());
                attached++;
            } catch (IllegalArgumentException | IOException e) {
                warnings.add((im.url() == null ? f.getOriginalFilename() : im.url()) + ": " + e.getMessage());
            }
        }

        ProductSource s = new ProductSource();
        s.setProductId(id);
        s.setSourceUrl(meta.url());
        s.setTitle(meta.title());
        s.setRawText(text.length() > MAX_TEXT ? text.substring(0, MAX_TEXT) : text);
        sources.save(s);
        jobs.enqueue(t.getId(), id, JobType.EXTRACT, null);
        return new Result(id, code, attached, warnings);
    }

    public Optional<ProductSource> source(Long tenantId, Long productId) {
        products.findByIdAndTenantId(productId, tenantId).orElseThrow(() -> new NotFoundException("product", productId));
        return sources.findById(productId);
    }

    /** {접두어}{yyMM}{3자리 순번}: CP2610001. 접두어가 없으면 판매자 코드 앞 글자 */
    String nextCode(Tenant t) {
        String prefix = t.getProductCodePrefix() != null && !t.getProductCodePrefix().isBlank() ? t.getProductCodePrefix()
                : t.getCode().replaceAll("[^a-z0-9]", "").toUpperCase(Locale.ROOT).substring(0, Math.min(2, t.getCode().length()));
        prefix = prefix.replaceAll("[^A-Za-z0-9-]", "");
        String base = prefix + LocalDate.now(ZoneId.of("Asia/Seoul")).format(DateTimeFormatter.ofPattern("yyMM"));
        int max = products.findCodesStartingWith(t.getId(), base).stream()
                .map(c -> c.substring(base.length()))
                .filter(s -> s.matches("\\d{3,}"))
                .mapToInt(Integer::parseInt).max().orElse(0);
        return base + String.format("%03d", max + 1);
    }

    private static String extension(MultipartFile f) {
        String ct = f.getContentType() == null ? "" : f.getContentType().toLowerCase(Locale.ROOT);
        String name = f.getOriginalFilename() == null ? "" : f.getOriginalFilename().toLowerCase(Locale.ROOT);
        if (ct.contains("png") || name.endsWith(".png")) {
            return "png";
        }
        if (ct.contains("webp") || name.endsWith(".webp")) {
            return "webp";
        }
        return "jpg";
    }
}
