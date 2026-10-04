package com.autoreg.image;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Stream;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import com.autoreg.common.ConflictException;
import com.autoreg.common.NotFoundException;
import com.autoreg.image.ImageDtos.AssignRequest;
import com.autoreg.image.ImageDtos.Matched;
import com.autoreg.image.ImageDtos.Unmatched;
import com.autoreg.image.ImageDtos.UnmatchedFile;
import com.autoreg.image.ImageDtos.UploadResult;
import com.autoreg.product.ImageSlot;
import com.autoreg.product.Product;
import com.autoreg.product.ProductImage;
import com.autoreg.product.ProductImage.SourceType;
import com.autoreg.product.ProductRepository;
import com.autoreg.tenant.Tenant;
import com.autoreg.tenant.TenantService;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class ImageService {

    private final ImageStorage storage;
    private final ProductRepository products;
    private final TenantService tenants;

    /**
     * 파일명 규칙으로 상품·슬롯을 찾아 붙인다. 못 찾은 파일은 판매자의 _unmatched 폴더에 남긴다.
     * 같은 슬롯·순번 이미지가 이미 있으면 교체한다.
     */
    @Transactional
    public UploadResult upload(Long tenantId, List<MultipartFile> files) {
        Tenant tenant = tenants.get(tenantId);
        List<Matched> matched = new ArrayList<>();
        List<Unmatched> unmatched = new ArrayList<>();
        for (MultipartFile file : files) {
            String original = ImageStorage.safeName(file.getOriginalFilename());
            byte[] bytes = bytes(file);
            try {
                Optional<ImageFilename> parsed = ImageFilename.parse(original);
                String reason = null;
                Product product = null;
                if (parsed.isEmpty()) {
                    reason = "파일명 규칙({상품코드}_{슬롯}_{순번}.jpg)에 맞지 않습니다";
                } else {
                    product = products.findByTenantIdAndCode(tenantId, parsed.get().productCode()).orElse(null);
                    if (product == null) {
                        reason = "상품코드 " + parsed.get().productCode() + " 가 이 판매자에 없습니다";
                    } else if (!product.getStatus().editable()) {
                        reason = "상품이 수정할 수 없는 상태입니다 (" + product.getStatus() + ")";
                    }
                }
                if (reason != null) {
                    storage.storeUnmatched(tenant.getCode(), original, bytes);
                    unmatched.add(new Unmatched(original, reason));
                    continue;
                }
                ImageFilename f = parsed.get();
                ProductImage img = attach(tenant, product, f.slot(), f.seq(), f.extension(), bytes, SourceType.UPLOAD, null);
                matched.add(new Matched(original, product.getId(), product.getCode(), f.slot().value(), f.seq(), img.getId()));
            } catch (IllegalArgumentException e) {
                unmatched.add(new Unmatched(original, e.getMessage()));
            }
        }
        return new UploadResult(matched, unmatched);
    }

    public List<UnmatchedFile> unmatched(Long tenantId) {
        Path dir = storage.unmatchedDir(tenants.get(tenantId).getCode());
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (Stream<Path> s = Files.list(dir)) {
            return s.filter(Files::isRegularFile)
                    .filter(p -> !p.getFileName().toString().startsWith("."))
                    .map(p -> {
                        try {
                            return new UnmatchedFile(p.getFileName().toString(), Files.size(p),
                                    Files.getLastModifiedTime(p).toInstant().atOffset(ZoneOffset.UTC));
                        } catch (IOException e) {
                            throw new UncheckedIOException(e);
                        }
                    })
                    .sorted(Comparator.comparing(UnmatchedFile::modifiedAt).reversed())
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** 매칭 실패 파일을 상품·슬롯에 수동 배정한다 */
    @Transactional
    public Matched assign(Long tenantId, AssignRequest req) {
        Tenant tenant = tenants.get(tenantId);
        Product product = editableProduct(tenantId, req.productId());
        ImageSlot slot = ImageSlot.of(req.slot()).orElseThrow(() -> new IllegalArgumentException("알 수 없는 슬롯: " + req.slot()));
        String name = ImageStorage.safeName(req.filename());
        Path file = storage.unmatchedDir(tenant.getCode()).resolve(name);
        if (!Files.isRegularFile(file)) {
            throw new NotFoundException("unmatched file", name);
        }
        byte[] bytes;
        try {
            bytes = Files.readAllBytes(file);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        ProductImage img = attach(tenant, product, slot, req.seq(), extension(name), bytes, SourceType.UPLOAD, null);
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return new Matched(name, product.getId(), product.getCode(), slot.value(), req.seq(), img.getId());
    }

    @Transactional
    public void deleteUnmatched(Long tenantId, String filename) {
        Path file = storage.unmatchedDir(tenants.get(tenantId).getCode()).resolve(ImageStorage.safeName(filename));
        try {
            if (!Files.deleteIfExists(file)) {
                throw new NotFoundException("unmatched file", filename);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Transactional
    public void deleteImage(Long tenantId, Long productId, Long imageId) {
        Product product = editableProduct(tenantId, productId);
        ProductImage img = product.getImages().stream().filter(i -> i.getId().equals(imageId)).findFirst()
                .orElseThrow(() -> new NotFoundException("image", imageId));
        product.getImages().remove(img);
        storage.deleteQuietly(img.getPath());
        storage.deleteQuietly(img.getThumbPath());
    }

    /** 업로드·수동 배정·웹 가져오기가 모두 이 경로로 상품에 이미지를 붙인다 */
    @Transactional
    public ProductImage attach(Tenant tenant, Product product, ImageSlot slot, int seq, String extension, byte[] bytes,
            SourceType sourceType, String sourceUrl) {
        String storedName = String.format("%s_%02d.%s", slot.value(), seq, extension);
        ImageStorage.Stored stored = storage.storeProductImage(tenant.getCode(), product.getCode(), storedName, bytes);
        ProductImage img = product.getImages().stream()
                .filter(i -> i.getSlot() == slot && i.getSeq() == seq).findFirst()
                .orElseGet(() -> {
                    ProductImage n = new ProductImage();
                    n.setSlot(slot);
                    n.setSeq(seq);
                    product.addImage(n);
                    return n;
                });
        if (img.getPath() != null && !img.getPath().equals(stored.path())) {
            storage.deleteQuietly(img.getPath()); // 확장자가 바뀐 교체
        }
        img.setPath(stored.path());
        img.setThumbPath(stored.thumbPath());
        img.setWidth(stored.width());
        img.setHeight(stored.height());
        img.setSha256(stored.sha256());
        img.setSourceType(sourceType);
        img.setSourceUrl(sourceUrl);
        img.setCreatedAt(OffsetDateTime.now());
        products.saveAndFlush(product);
        return img;
    }

    private Product editableProduct(Long tenantId, Long productId) {
        Product p = products.findByIdAndTenantId(productId, tenantId)
                .orElseThrow(() -> new NotFoundException("product", productId));
        if (!p.getStatus().editable()) {
            throw new ConflictException("현재 상태(" + p.getStatus() + ")에서는 이미지를 바꿀 수 없습니다");
        }
        return p;
    }

    static String extension(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".png")) {
            return "png";
        }
        if (lower.endsWith(".webp")) {
            return "webp";
        }
        return "jpg";
    }

    private static byte[] bytes(MultipartFile f) {
        try {
            return f.getBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
