package com.autoreg.webimage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.autoreg.tenant.Tenant;

class WebImageUnitTest {

    @Test
    void extractsOgImageLazyAttrsAndLargestSrcset() {
        String html = """
                <html><head><meta property="og:image" content="/og.jpg"></head><body>
                <img src="/a.jpg">
                <img src="/placeholder.gif" data-src="//cdn.example.com/lazy.jpg">
                <img srcset="/s.jpg 300w, /l.jpg 1200w, /m.jpg 800w" src="/s.jpg">
                <img src="/logo.png"><img src="data:image/png;base64,xx"><img src="/icon/x.png">
                <img src="/a.jpg">
                </body></html>""";
        List<URI> out = CandidateExtractor.extract(html, URI.create("https://shop.example.com/p/1"), 10);
        assertThat(out).extracting(URI::toString).containsExactly(
                "https://shop.example.com/og.jpg",
                "https://shop.example.com/a.jpg",
                "https://cdn.example.com/lazy.jpg",
                "https://shop.example.com/l.jpg");
    }

    @Test
    void blocksInternalAddressesAndOtherSchemes() {
        SafeFetcher f = new SafeFetcher(false);
        for (String u : List.of("http://127.0.0.1/x", "http://192.168.0.42:5432/", "http://10.0.0.1/", "http://169.254.169.254/latest",
                "http://[::1]/", "file:///etc/passwd", "ftp://example.com/x")) {
            assertThatThrownBy(() -> f.check(URI.create(u))).as(u).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void pageDomainMustBeAllowedForTenant() {
        Tenant t = new Tenant();
        t.setAllowedImageDomains(List.of("wholesale.co.kr", "*.dome.com"));
        WebImageService.requireAllowed(t, URI.create("https://wholesale.co.kr/p/1"));
        WebImageService.requireAllowed(t, URI.create("https://m.wholesale.co.kr/p/1"));
        WebImageService.requireAllowed(t, URI.create("https://img.dome.com/p"));
        assertThatThrownBy(() -> WebImageService.requireAllowed(t, URI.create("https://evilwholesale.co.kr/p")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> WebImageService.requireAllowed(t, URI.create("https://other.com/p")))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
