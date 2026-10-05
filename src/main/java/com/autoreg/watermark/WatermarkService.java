package com.autoreg.watermark;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.autoreg.common.ConflictException;
import com.autoreg.common.NotFoundException;
import com.autoreg.image.ImageStorage;
import com.autoreg.product.Product;
import com.autoreg.product.ProductImage;
import com.autoreg.product.ProductRepository;

import lombok.RequiredArgsConstructor;

/**
 * 워터마크 템플릿 만들기·적용·되돌리기. 원본은 같은 폴더의 orig/ 에 보관하고, 템플릿 크기와 같은 사진에만 적용한다.
 * (사용 허락을 받은 사진에만 쓴다는 전제. 템플릿은 반복 격자형 워터마크에 맞춰져 있다)
 */
@Service
@RequiredArgsConstructor
public class WatermarkService {

    static final String DATA_IMAGES = "images/";

    private final ImagingClient imaging;
    private final ImageStorage storage;
    private final ProductRepository products;

    public record Applied(int processed, int skipped, List<String> errors) {}

    public List<ImagingClient.Template> templates() {
        return imaging.templates();
    }

    /** 이 상품에서 가장 많은 크기의 사진들로 템플릿을 만든다 (같은 워터마크가 같은 자리에 있어야 한다) */
    @Transactional(readOnly = true)
    public ImagingClient.Template createTemplate(String name, Long tenantId, Long productId) {
        Product p = find(tenantId, productId);
        Map<String, List<ProductImage>> bySize = p.getImages().stream().filter(i -> i.getWidth() != null)
                .collect(Collectors.groupingBy(i -> i.getWidth() + "x" + i.getHeight()));
        List<ProductImage> group = bySize.values().stream().max(Comparator.comparingInt(List::size)).orElse(List.of());
        if (group.size() < 6) {
            throw new IllegalArgumentException("같은 크기 사진이 6장 이상 있는 상품을 고르세요 (가장 많은 크기가 " + group.size() + "장)");
        }
        List<String> paths = group.stream()
                .map(i -> DATA_IMAGES + (i.getOriginalPath() != null ? i.getOriginalPath() : i.getPath())).toList();
        return imaging.estimate(name, paths);
    }

    @Transactional
    public Applied apply(Long tenantId, Long productId, String template) {
        return apply(tenantId, productId, template, false);
    }

    /** 템플릿 크기와 같은 사진에서 워터마크를 지운다. redo 면 이미 지운 사진도 보관한 원본에서 다시 처리한다 */
    @Transactional
    public Applied apply(Long tenantId, Long productId, String template, boolean redo) {
        Product p = find(tenantId, productId);
        if (!p.getStatus().editable()) {
            throw new ConflictException("현재 상태(" + p.getStatus() + ")에서는 사진을 바꿀 수 없습니다");
        }
        ImagingClient.Template t = imaging.templates().stream().filter(x -> x.name().equals(template)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("워터마크 템플릿이 없습니다: " + template));
        int done = 0;
        int skipped = 0;
        List<String> errors = new ArrayList<>();
        for (ProductImage img : p.getImages()) {
            boolean again = redo && img.getOriginalPath() != null;
            if ((img.getOriginalPath() != null && !again) || img.getWidth() == null || img.getWidth() != t.width() || img.getHeight() != t.height()) {
                skipped++;
                continue;
            }
            String orig = again ? img.getOriginalPath() : storage.moveToOriginals(img.getPath());
            try {
                imaging.remove(template, DATA_IMAGES + orig, DATA_IMAGES + img.getPath());
            } catch (RuntimeException e) {
                if (!again) {
                    storage.moveBack(orig, img.getPath());
                }
                errors.add(img.getSlot().value() + " " + img.getSeq() + ": " + e.getMessage());
                continue;
            }
            ImageStorage.Stored s = storage.refresh(img.getPath(), img.getThumbPath());
            img.setOriginalPath(orig);
            img.setWatermarkTemplate(template);
            img.setSha256(s.sha256());
            done++;
        }
        return new Applied(done, skipped, errors);
    }

    @Transactional
    public void restore(Long tenantId, Long productId, Long imageId) {
        Product p = find(tenantId, productId);
        ProductImage img = p.getImages().stream().filter(i -> i.getId().equals(imageId)).findFirst()
                .orElseThrow(() -> new NotFoundException("image", imageId));
        if (img.getOriginalPath() == null) {
            return;
        }
        storage.moveBack(img.getOriginalPath(), img.getPath());
        ImageStorage.Stored s = storage.refresh(img.getPath(), img.getThumbPath());
        img.setOriginalPath(null);
        img.setWatermarkTemplate(null);
        img.setSha256(s.sha256());
    }

    private Product find(Long tenantId, Long productId) {
        return products.findByIdAndTenantId(productId, tenantId).orElseThrow(() -> new NotFoundException("product", productId));
    }
}
