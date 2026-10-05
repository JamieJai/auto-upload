package com.autoreg.channel.adapter.naver;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.bcrypt.BCrypt;
import org.springframework.stereotype.Component;

import com.autoreg.channel.adapter.ChannelException;

import lombok.extern.slf4j.Slf4j;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 네이버 커머스API 호출. 인증: client_secret_sign = base64(bcrypt(clientId + "_" + timestamp, clientSecret)).
 * 토큰은 계정마다 만료 1분 전까지 재사용한다. 오류는 ChannelException 으로 바꿔 던진다
 * (400 → 사용자가 고칠 것, 401 → 토큰 재발급 후 1회 재시도, 429·5xx·네트워크 → 재시도).
 */
@Slf4j
@Component
public class NaverCommerceClient {

    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {};

    private final String base;
    private final JsonMapper json;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final Map<String, Token> tokens = new ConcurrentHashMap<>();
    /** 이미지 업로드를 연달아 보내면 429 가 난다 (실측 2026-10-05). 계정마다 간격을 둔다 */
    private final Map<String, Long> lastUpload = new ConcurrentHashMap<>();
    private final long uploadGapMs;

    public NaverCommerceClient(@Value("${autoreg.naver.base-url:https://api.commerce.naver.com/external}") String base,
            @Value("${autoreg.naver.upload-gap-ms:700}") long uploadGapMs, JsonMapper json) {
        this.base = base;
        this.uploadGapMs = uploadGapMs;
        this.json = json;
    }

    public record Credentials(String clientId, String clientSecret) {
        public static Credentials of(Map<String, String> m) {
            String id = m.get("clientId");
            String secret = m.get("clientSecret");
            if (id == null || id.isBlank() || secret == null || secret.isBlank()) {
                throw ChannelException.invalid("스마트스토어 채널 계정에 clientId/clientSecret 이 없습니다");
            }
            return new Credentials(id.trim(), secret.trim());
        }
    }

    private record Token(String value, Instant expiresAt) {}

    /** 판매자 상품코드로 등록된 원상품번호 */
    public Optional<String> findBySellerCode(Credentials c, String sellerCode) {
        JsonNode res = send(c, "POST", "/v1/products/search",
                Map.of("searchKeywordType", "SELLER_CODE", "sellerManagementCode", sellerCode, "page", 1, "size", 10));
        for (JsonNode p : res.path("contents")) {
            // 검색은 부분일치일 수 있어 원상품의 판매자 코드를 다시 확인한다
            for (JsonNode cp : p.path("channelProducts")) {
                if (sellerCode.equals(cp.path("sellerManagementCode").asString(""))) {
                    return Optional.of(p.path("originProductNo").asString());
                }
            }
        }
        return Optional.empty();
    }

    /** 이미지 1장을 네이버 이미지 서버에 올리고 URL 을 받는다 (상품 등록에는 이 URL 만 쓸 수 있다) */
    public String uploadImage(Credentials c, Path file) {
        byte[] bytes;
        try {
            bytes = Files.readAllBytes(file);
        } catch (IOException e) {
            throw ChannelException.invalid("이미지 파일을 읽을 수 없습니다: " + file.getFileName());
        }
        pace(c.clientId());
        String name = file.getFileName().toString();
        // 네이버는 JPG·GIF·PNG·BMP 만 받는다. WebP 는 JPG 로 바꿔 올린다
        if (name.toLowerCase(java.util.Locale.ROOT).endsWith(".webp")) {
            bytes = toJpeg(bytes, name);
            name = name.substring(0, name.length() - 5) + ".jpg";
        }
        String boundary = "----autoreg" + UUID.randomUUID();
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        String type = name.endsWith(".png") ? "image/png" : "image/jpeg";
        body.writeBytes(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"imageFiles\"; filename=\"" + name
                + "\"\r\nContent-Type: " + type + "\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        body.writeBytes(bytes);
        body.writeBytes(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        JsonNode res = sendRaw(c, "POST", "/v1/product-images/upload", "multipart/form-data; boundary=" + boundary,
                body.toByteArray(), true);
        String url = res.path("images").path(0).path("url").asString("");
        if (url.isEmpty()) {
            throw new ChannelException("이미지 업로드 응답에 URL 이 없습니다", true, null, toMap(res));
        }
        return url;
    }

    /** 상품 등록. 응답 원문을 돌려준다 (originProductNo, smartstoreChannelProductNo) */
    public Map<String, Object> registerProduct(Credentials c, Map<String, Object> payload) {
        return toMap(send(c, "POST", "/v2/products", payload));
    }

    /** 읽기 전용 조회 (원산지 코드, 카테고리 등). JSON 을 Map/List 로 */
    public Object getJson(Credentials c, String path) {
        return json.convertValue(send(c, "GET", path, null), Object.class);
    }

    /** 상품 목록 한 쪽 (읽기 전용) */
    public Map<String, Object> searchProducts(Credentials c, int page, int size) {
        return toMap(send(c, "POST", "/v1/products/search", Map.of("page", page, "size", size)));
    }

    /** 채널상품 조회 (등록 후 재조회 검증용) */
    public Map<String, Object> getChannelProduct(Credentials c, String channelProductNo) {
        return toMap(send(c, "GET", "/v2/products/channel-products/" + URLEncoder.encode(channelProductNo, StandardCharsets.UTF_8), null));
    }

    static byte[] toJpeg(byte[] webp, String name) {
        try {
            java.awt.image.BufferedImage src = javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(webp));
            if (src == null) {
                throw ChannelException.invalid("WebP 이미지를 읽을 수 없습니다: " + name);
            }
            java.awt.image.BufferedImage rgb = new java.awt.image.BufferedImage(src.getWidth(), src.getHeight(),
                    java.awt.image.BufferedImage.TYPE_INT_RGB);
            java.awt.Graphics2D g = rgb.createGraphics();
            g.setColor(java.awt.Color.WHITE);
            g.fillRect(0, 0, rgb.getWidth(), rgb.getHeight());
            g.drawImage(src, 0, 0, null);
            g.dispose();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            javax.imageio.ImageWriter w = javax.imageio.ImageIO.getImageWritersByFormatName("jpg").next();
            javax.imageio.ImageWriteParam param = w.getDefaultWriteParam();
            param.setCompressionMode(javax.imageio.ImageWriteParam.MODE_EXPLICIT);
            param.setCompressionQuality(0.92f);
            try (javax.imageio.stream.ImageOutputStream ios = javax.imageio.ImageIO.createImageOutputStream(out)) {
                w.setOutput(ios);
                w.write(null, new javax.imageio.IIOImage(rgb, null, null), param);
            } finally {
                w.dispose();
            }
            return out.toByteArray();
        } catch (IOException e) {
            throw ChannelException.invalid("WebP → JPG 변환 실패: " + name);
        }
    }

    /** 원상품 조회 (템플릿 가져오기용, 읽기 전용) */
    public Map<String, Object> getOriginProduct(Credentials c, String originProductNo) {
        return toMap(send(c, "GET", "/v2/products/origin-products/" + URLEncoder.encode(originProductNo, StandardCharsets.UTF_8), null));
    }

    private void pace(String clientId) {
        synchronized (lastUpload) {
            long wait = lastUpload.getOrDefault(clientId, 0L) + uploadGapMs - System.currentTimeMillis();
            if (wait > 0) {
                try {
                    Thread.sleep(wait);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            lastUpload.put(clientId, System.currentTimeMillis());
        }
    }

    private JsonNode send(Credentials c, String method, String path, Object body) {
        byte[] bytes = body == null ? null : json.writeValueAsBytes(body);
        return sendRaw(c, method, path, "application/json", bytes, true);
    }

    private JsonNode sendRaw(Credentials c, String method, String path, String contentType, byte[] body, boolean retryAuth) {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(60))
                .header("Authorization", "Bearer " + token(c)).header("Accept", "application/json");
        if (body != null) {
            b.header("Content-Type", contentType).method(method, HttpRequest.BodyPublishers.ofByteArray(body));
        } else {
            b.method(method, HttpRequest.BodyPublishers.noBody());
        }
        HttpResponse<String> res;
        try {
            res = http.send(b.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new ChannelException("네이버 API 연결 실패: " + e.getClass().getSimpleName(), true, null, null);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ChannelException("중단됨", true, null, null);
        }
        int status = res.statusCode();
        if (status == 401 && retryAuth) {
            tokens.remove(c.clientId());
            return sendRaw(c, method, path, contentType, body, false);
        }
        JsonNode node = parse(res.body());
        if (status >= 200 && status < 300) {
            return node;
        }
        String message = describe(node, status);
        Map<String, Object> raw = toMap(node);
        if (status == 429) {
            throw new ChannelException("네이버 API 호출 한도 초과: " + message, true, Duration.ofMinutes(1), raw);
        }
        if (status >= 500) {
            throw new ChannelException("네이버 서버 오류(" + status + "): " + message, true, null, raw);
        }
        if (status == 403) {
            throw new ChannelException("권한 없음(403): " + message + " — 커머스API센터에서 API 권한·호출 IP 를 확인하세요", false, null, raw);
        }
        throw new ChannelException("네이버가 요청을 거부했습니다(" + status + "): " + message, false, null, raw);
    }

    private String token(Credentials c) {
        Token t = tokens.get(c.clientId());
        if (t != null && Instant.now().isBefore(t.expiresAt())) {
            return t.value();
        }
        String ts = String.valueOf(System.currentTimeMillis());
        String sign;
        try {
            sign = Base64.getEncoder().encodeToString(
                    BCrypt.hashpw(c.clientId() + "_" + ts, c.clientSecret()).getBytes(StandardCharsets.UTF_8));
        } catch (IllegalArgumentException e) {
            throw ChannelException.invalid("clientSecret 형식이 올바르지 않습니다 ($2a$ 로 시작하는 값)");
        }
        String form = form(Map.of("client_id", c.clientId(), "timestamp", ts, "client_secret_sign", sign,
                "grant_type", "client_credentials", "type", "SELF"));
        HttpResponse<String> res;
        try {
            res = http.send(HttpRequest.newBuilder(URI.create(base + "/v1/oauth2/token")).timeout(Duration.ofSeconds(20))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(form)).build(), HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new ChannelException("네이버 토큰 발급 연결 실패", true, null, null);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ChannelException("중단됨", true, null, null);
        }
        JsonNode node = parse(res.body());
        if (res.statusCode() != 200) {
            boolean retry = res.statusCode() >= 500 || res.statusCode() == 429;
            throw new ChannelException("네이버 토큰 발급 실패(" + res.statusCode() + "): " + describe(node, res.statusCode())
                    + (retry ? "" : " — clientId/clientSecret 과 API 호출 IP 등록을 확인하세요"), retry, null, null);
        }
        long ttl = node.path("expires_in").asLong(3600);
        Token fresh = new Token(node.path("access_token").asString(), Instant.now().plusSeconds(Math.max(60, ttl - 60)));
        tokens.put(c.clientId(), fresh);
        return fresh.value();
    }

    /** 400 응답의 invalidInputs 를 사람이 읽을 수 있게 합친다 */
    static String describe(JsonNode node, int status) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return "HTTP " + status;
        }
        StringBuilder sb = new StringBuilder(node.path("message").asString("HTTP " + status));
        JsonNode inputs = node.path("invalidInputs");
        if (inputs.isArray() && !inputs.isEmpty()) {
            sb.append(" [");
            int i = 0;
            for (JsonNode in : inputs) {
                if (i++ > 0) {
                    sb.append("; ");
                }
                sb.append(in.path("name").asString("")).append(": ").append(in.path("message").asString(""));
                if (i >= 8) {
                    sb.append("; …");
                    break;
                }
            }
            sb.append(']');
        }
        return sb.toString();
    }

    private JsonNode parse(String body) {
        if (body == null || body.isBlank()) {
            return json.createObjectNode();
        }
        try {
            return json.readTree(body);
        } catch (RuntimeException e) {
            return json.createObjectNode().put("message", body.length() > 300 ? body.substring(0, 300) : body);
        }
    }

    private Map<String, Object> toMap(JsonNode node) {
        return node == null ? Map.of() : json.convertValue(node, MAP);
    }

    private static String form(Map<String, String> m) {
        StringBuilder sb = new StringBuilder();
        new LinkedHashMap<>(m).forEach((k, v) -> {
            if (!sb.isEmpty()) {
                sb.append('&');
            }
            sb.append(URLEncoder.encode(k, StandardCharsets.UTF_8)).append('=').append(URLEncoder.encode(v, StandardCharsets.UTF_8));
        });
        return sb.toString();
    }

}
