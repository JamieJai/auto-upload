package com.autoreg.image;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.autoreg.image.ImageDtos.AssignRequest;
import com.autoreg.image.ImageDtos.Matched;
import com.autoreg.image.ImageDtos.UnmatchedFile;
import com.autoreg.image.ImageDtos.UploadResult;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/tenants/{tenantId}")
@RequiredArgsConstructor
public class ImageController {

    private final ImageService service;

    @PostMapping(path = "/images", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public UploadResult upload(@PathVariable Long tenantId, @RequestPart("files") List<MultipartFile> files) {
        return service.upload(tenantId, files);
    }

    @PostMapping(path = "/products/{productId}/images", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public List<Matched> addToSlot(@PathVariable Long tenantId, @PathVariable Long productId, @RequestParam String slot,
            @RequestPart("files") List<MultipartFile> files) {
        return service.addToSlot(tenantId, productId,
                com.autoreg.product.ImageSlot.of(slot).orElseThrow(() -> new IllegalArgumentException("알 수 없는 슬롯: " + slot)),
                files);
    }

    @GetMapping("/images/unmatched")
    public List<UnmatchedFile> unmatched(@PathVariable Long tenantId) {
        return service.unmatched(tenantId);
    }

    @PostMapping("/images/unmatched/assign")
    public Matched assign(@PathVariable Long tenantId, @Valid @RequestBody AssignRequest req) {
        return service.assign(tenantId, req);
    }

    @DeleteMapping("/images/unmatched")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteUnmatched(@PathVariable Long tenantId, @RequestParam String filename) {
        service.deleteUnmatched(tenantId, filename);
    }

    @DeleteMapping("/products/{productId}/images/{imageId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteImage(@PathVariable Long tenantId, @PathVariable Long productId, @PathVariable Long imageId) {
        service.deleteImage(tenantId, productId, imageId);
    }
}
