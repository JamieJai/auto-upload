package com.autoreg.watermark;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/** 이미지 처리 서비스(imaging, 내부망) 호출. 경로는 /data 기준 상대경로로 넘긴다 */
@Component
public class ImagingClient {

    private final String base;
    private final JsonMapper json;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    public ImagingClient(@Value("${autoreg.imaging.url:http://imaging:8090}") String base, JsonMapper json) {
        this.base = base;
        this.json = json;
    }

    public record Template(String name, int width, int height, int samples, int copies) {}

    public List<Template> templates() {
        return json.readValue(call("GET", "/templates", null, Duration.ofSeconds(20)), new TypeReference<List<Template>>() {});
    }

    public Template estimate(String name, List<String> dataPaths) {
        return json.readValue(call("POST", "/templates", Map.of("name", name, "images", dataPaths), Duration.ofMinutes(5)), Template.class);
    }

    public void remove(String template, String srcDataPath, String dstDataPath) {
        // 리터치(LaMa, CPU)는 사진 한 장에 20초 안팎
        call("POST", "/remove", Map.of("template", template, "src", srcDataPath, "dst", dstDataPath), Duration.ofMinutes(4));
    }

    private String call(String method, String path, Object body, Duration timeout) {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base + path)).timeout(timeout).header("Content-Type", "application/json");
        b.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body), StandardCharsets.UTF_8));
        try {
            HttpResponse<String> res = http.send(b.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (res.statusCode() >= 400) {
                String msg = res.body();
                try {
                    msg = json.readTree(res.body()).path("message").asString(msg);
                } catch (RuntimeException ignored) {
                    // 본문이 JSON 이 아니면 그대로
                }
                throw new IllegalArgumentException("이미지 처리 실패: " + msg);
            }
            return res.body();
        } catch (IOException e) {
            throw new IllegalStateException("이미지 처리 서비스에 연결할 수 없습니다: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("중단됨");
        }
    }
}
