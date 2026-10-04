package com.autoreg.webimage;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Locale;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 외부 URL 을 안전하게 받는다. http/https 만, 사설·루프백·링크로컬 주소는 거부(SSRF 방지),
 * 리다이렉트는 직접 따라가며 매번 다시 검사, 크기 상한을 넘으면 중단.
 */
@Component
public class SafeFetcher {

    static final String UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0 Safari/537.36";

    private final HttpClient http = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    private final boolean allowPrivate;

    public SafeFetcher(@Value("${autoreg.web-fetch.allow-private:false}") boolean allowPrivate) {
        this.allowPrivate = allowPrivate;
    }

    public record Fetched(URI finalUri, String contentType, byte[] body) {}

    public Fetched get(URI uri, long maxBytes, String accept) {
        URI current = uri;
        for (int hop = 0; hop < 5; hop++) {
            check(current);
            HttpRequest req = HttpRequest.newBuilder(current).timeout(Duration.ofSeconds(20))
                    .header("User-Agent", UA).header("Accept", accept).header("Accept-Language", "ko-KR,ko;q=0.9")
                    .GET().build();
            HttpResponse<InputStream> res;
            try {
                res = http.send(req, HttpResponse.BodyHandlers.ofInputStream());
            } catch (IOException e) {
                throw new IllegalArgumentException("가져올 수 없습니다: " + current.getHost() + " (" + e.getClass().getSimpleName() + ")");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("중단됨");
            }
            int status = res.statusCode();
            if (status >= 300 && status < 400) {
                String loc = res.headers().firstValue("Location").orElseThrow(() -> new IllegalArgumentException("리다이렉트 위치가 없습니다"));
                closeQuietly(res.body());
                current = current.resolve(loc);
                continue;
            }
            if (status != 200) {
                closeQuietly(res.body());
                throw new IllegalArgumentException("응답 " + status + ": " + current);
            }
            return new Fetched(current, res.headers().firstValue("Content-Type").orElse(""), read(res.body(), maxBytes));
        }
        throw new IllegalArgumentException("리다이렉트가 너무 많습니다");
    }

    /** 내부망으로 요청이 나가지 않게 한다 */
    void check(URI uri) {
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https")) {
            throw new IllegalArgumentException("http/https 주소만 가져올 수 있습니다");
        }
        if (uri.getHost() == null) {
            throw new IllegalArgumentException("주소가 올바르지 않습니다");
        }
        if (allowPrivate) {
            return;
        }
        try {
            for (InetAddress a : InetAddress.getAllByName(uri.getHost())) {
                if (a.isLoopbackAddress() || a.isSiteLocalAddress() || a.isLinkLocalAddress() || a.isAnyLocalAddress()
                        || a.isMulticastAddress() || isUniqueLocalV6(a)) {
                    throw new IllegalArgumentException("내부망 주소는 가져올 수 없습니다: " + uri.getHost());
                }
            }
        } catch (UnknownHostException e) {
            throw new IllegalArgumentException("도메인을 찾을 수 없습니다: " + uri.getHost());
        }
    }

    private static boolean isUniqueLocalV6(InetAddress a) {
        byte[] b = a.getAddress();
        return b.length == 16 && (b[0] & 0xfe) == 0xfc;
    }

    private static byte[] read(InputStream in, long max) {
        try (in; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buf = new byte[16384];
            long total = 0;
            int n;
            while ((n = in.read(buf)) > 0) {
                total += n;
                if (total > max) {
                    throw new IllegalArgumentException("파일이 너무 큽니다 (" + max / 1024 / 1024 + "MB 초과)");
                }
                out.write(buf, 0, n);
            }
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalArgumentException("읽기 실패: " + e.getMessage());
        }
    }

    private static void closeQuietly(InputStream in) {
        try {
            in.close();
        } catch (IOException ignored) {
            // 버리는 응답
        }
    }
}
