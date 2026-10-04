package com.autoreg.channel.naver;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.security.crypto.bcrypt.BCrypt;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/** 커머스API 흉내. 토큰 서명을 실제 규칙대로 검사하고, 받은 요청을 기록한다 */
public class FakeNaver implements AutoCloseable {

    public static final String CLIENT_ID = "test-client";
    public static final String CLIENT_SECRET = BCrypt.gensalt(4);

    public final HttpServer server;
    public final List<String> requests = new ArrayList<>();
    public final List<String> registerBodies = new ArrayList<>();
    public final AtomicInteger tokenCalls = new AtomicInteger();
    public volatile String existingCode;
    public volatile int registerStatus = 200;
    public volatile boolean expireNextToken;

    public FakeNaver() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.start();
    }

    public String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private void handle(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getPath();
        String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        synchronized (requests) {
            requests.add(ex.getRequestMethod() + " " + path);
        }
        if (path.equals("/v1/oauth2/token")) {
            tokenCalls.incrementAndGet();
            Map<String, String> f = new HashMap<>();
            for (String kv : body.split("&")) {
                String[] p = kv.split("=", 2);
                f.put(p[0], URLDecoder.decode(p[1], StandardCharsets.UTF_8));
            }
            String expected = BCrypt.hashpw(f.get("client_id") + "_" + f.get("timestamp"), CLIENT_SECRET);
            String got = new String(Base64.getDecoder().decode(f.get("client_secret_sign")), StandardCharsets.UTF_8);
            if (!CLIENT_ID.equals(f.get("client_id")) || !expected.equals(got) || !"SELF".equals(f.get("type"))) {
                send(ex, 400, "{\"code\":\"BAD\",\"message\":\"잘못된 서명\"}");
                return;
            }
            send(ex, 200, "{\"access_token\":\"tok-" + tokenCalls.get() + "\",\"expires_in\":10800,\"token_type\":\"Bearer\"}");
            return;
        }
        String auth = ex.getRequestHeaders().getFirst("Authorization");
        if (auth == null || !auth.startsWith("Bearer tok-") || (expireNextToken && auth.equals("Bearer tok-1"))) {
            expireNextToken = false;
            send(ex, 401, "{\"code\":\"GW.AUTHN\",\"message\":\"만료\"}");
            return;
        }
        switch (path) {
            case "/v1/products/search" -> {
                boolean hit = existingCode != null && body.contains("\"" + existingCode + "\"");
                send(ex, 200, hit
                        ? "{\"contents\":[{\"originProductNo\":777,\"channelProducts\":[{\"sellerManagementCode\":\"" + existingCode + "\"}]}],\"totalElements\":1}"
                        : "{\"contents\":[],\"totalElements\":0}");
            }
            case "/v1/product-images/upload" -> {
                if (!ex.getRequestHeaders().getFirst("Content-Type").startsWith("multipart/form-data") || !body.contains("name=\"imageFiles\"")) {
                    send(ex, 400, "{\"message\":\"no file\"}");
                    return;
                }
                send(ex, 200, "{\"images\":[{\"url\":\"https://shop-phinf.pstatic.net/fake/" + requests.size() + ".jpg\"}]}");
            }
            case "/v2/products" -> {
                synchronized (registerBodies) {
                    registerBodies.add(body);
                }
                if (registerStatus == 400) {
                    send(ex, 400, "{\"code\":\"BadRequest\",\"message\":\"요청 값 오류\",\"invalidInputs\":[{\"name\":\"originProduct.detailAttribute.originAreaInfo\",\"type\":\"NotNull\",\"message\":\"원산지는 필수입니다\"}]}");
                } else if (registerStatus == 500) {
                    send(ex, 500, "{\"message\":\"internal\"}");
                } else {
                    send(ex, 200, "{\"originProductNo\":13700000001,\"smartstoreChannelProductNo\":13700000002}");
                }
            }
            case "/v2/products/origin-products/123" -> send(ex, 200, """
                    {"originProduct":{"statusType":"SALE","deliveryInfo":{"deliveryType":"DELIVERY","deliveryCompany":"CJGLS",
                      "deliveryFee":{"deliveryFeeType":"FREE","baseFee":0},"claimDeliveryInfo":{"returnDeliveryFee":2500,"exchangeDeliveryFee":5000}},
                     "detailAttribute":{"originAreaInfo":{"originAreaCode":"00","content":"국산","plural":false},"taxType":"TAX",
                      "minorPurchasable":true,"certificationTargetExcludeContent":{"kcCertifiedProductExclusionYn":"TRUE"},
                      "naverShoppingSearchInfo":{"modelName":"x","brandName":"charming_point_","manufacturerName":"협력업체"}}},
                     "smartstoreChannelProduct":{"naverShoppingRegistration":true,"channelProductDisplayStatusType":"ON"}}""");
            default -> send(ex, 404, "{\"message\":\"not found " + path + "\"}");
        }
    }

    private static void send(HttpExchange ex, int status, String body) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json;charset=UTF-8");
        ex.sendResponseHeaders(status, b.length);
        ex.getResponseBody().write(b);
        ex.close();
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
