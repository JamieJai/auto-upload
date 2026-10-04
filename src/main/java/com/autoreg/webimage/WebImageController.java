package com.autoreg.webimage;

import java.util.List;

import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.autoreg.image.ImageDtos.Matched;
import com.autoreg.webimage.WebImageService.Candidate;
import com.autoreg.webimage.WebImageService.Pick;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/tenants/{tenantId}")
@RequiredArgsConstructor
public class WebImageController {

    private final WebImageService service;

    public record CandidatesRequest(@NotBlank String url) {}

    public record ImportRequest(@NotBlank String pageUrl, @NotEmpty @Size(max = 30) List<@Valid Pick> picks) {}

    @PostMapping("/web-images/candidates")
    public List<Candidate> candidates(@PathVariable Long tenantId, @Valid @RequestBody CandidatesRequest req) {
        return service.candidates(tenantId, req.url());
    }

    @PostMapping("/products/{productId}/web-images")
    public List<Matched> importPicks(@PathVariable Long tenantId, @PathVariable Long productId, @Valid @RequestBody ImportRequest req) {
        return service.importPicks(tenantId, productId, req.pageUrl(), req.picks());
    }
}
