package com.autoreg.webimage;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

/** 상품 페이지 HTML 에서 이미지 후보 URL 을 뽑는다: og:image, img src/지연로딩 속성, srcset 의 가장 큰 것 */
public final class CandidateExtractor {

    static final List<String> LAZY_ATTRS = List.of("data-src", "data-original", "data-lazy-src", "data-lazy", "data-zoom-image", "ec-data-src");

    private CandidateExtractor() {}

    public static List<URI> extract(String html, URI base, int limit) {
        Document doc = Jsoup.parse(html, base.toString());
        Set<String> out = new LinkedHashSet<>();
        for (Element m : doc.select("meta[property=og:image], meta[name=og:image]")) {
            add(out, base, m.attr("content"));
        }
        for (Element img : doc.select("img")) {
            String best = largestFromSrcset(img.attr("srcset"));
            for (String a : LAZY_ATTRS) {
                if (best == null && !img.attr(a).isBlank()) {
                    best = img.attr(a);
                }
            }
            add(out, base, best != null ? best : img.attr("src"));
        }
        for (Element s : doc.select("picture source[srcset]")) {
            add(out, base, largestFromSrcset(s.attr("srcset")));
        }
        List<URI> uris = new ArrayList<>();
        for (String s : out) {
            if (uris.size() >= limit) {
                break;
            }
            try {
                uris.add(URI.create(s));
            } catch (IllegalArgumentException ignored) {
                // 깨진 URL 은 버린다
            }
        }
        return uris;
    }

    static String largestFromSrcset(String srcset) {
        if (srcset == null || srcset.isBlank()) {
            return null;
        }
        String best = null;
        double bestW = -1;
        for (String part : srcset.split(",")) {
            String[] t = part.trim().split("\\s+");
            if (t.length == 0 || t[0].isBlank()) {
                continue;
            }
            double w = 1;
            if (t.length > 1) {
                try {
                    w = Double.parseDouble(t[1].replaceAll("[^0-9.]", ""));
                } catch (NumberFormatException ignored) {
                    w = 1;
                }
            }
            if (w > bestW) {
                bestW = w;
                best = t[0];
            }
        }
        return best;
    }

    private static void add(Set<String> out, URI base, String raw) {
        if (raw == null || raw.isBlank() || raw.startsWith("data:")) {
            return;
        }
        String abs;
        try {
            abs = base.resolve(raw.trim().replace(" ", "%20")).toString();
        } catch (IllegalArgumentException e) {
            return;
        }
        String lower = abs.toLowerCase(Locale.ROOT);
        if (!lower.startsWith("http") || lower.endsWith(".svg") || lower.endsWith(".gif") || lower.contains("/icon")
                || lower.contains("logo")) {
            return;
        }
        out.add(abs);
    }
}
