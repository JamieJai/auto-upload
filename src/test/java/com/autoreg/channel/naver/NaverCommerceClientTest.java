package com.autoreg.channel.naver;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.autoreg.channel.adapter.ChannelException;
import com.autoreg.channel.adapter.naver.NaverCommerceClient;
import com.autoreg.channel.adapter.naver.NaverCommerceClient.Credentials;

import tools.jackson.databind.json.JsonMapper;

class NaverCommerceClientTest {

    FakeNaver naver;
    NaverCommerceClient client;
    final Credentials creds = new Credentials(FakeNaver.CLIENT_ID, FakeNaver.CLIENT_SECRET);

    @BeforeEach
    void setUp() throws Exception {
        naver = new FakeNaver();
        client = new NaverCommerceClient(naver.baseUrl(), JsonMapper.builder().build());
    }

    @AfterEach
    void tearDown() {
        naver.close();
    }

    @Test
    void signsTokenRequestAndReusesToken() {
        assertThat(client.findBySellerCode(creds, "SS1")).isEmpty();
        naver.existingCode = "SS1";
        assertThat(client.findBySellerCode(creds, "SS1")).contains("777");
        assertThat(naver.tokenCalls.get()).isEqualTo(1);
    }

    @Test
    void wrongSecretIsNotRetryable() {
        var bad = new Credentials(FakeNaver.CLIENT_ID, "$2a$04$abcdefghijklmnopqrstuu");
        assertThatThrownBy(() -> client.findBySellerCode(bad, "SS1"))
                .isInstanceOfSatisfying(ChannelException.class, e -> {
                    assertThat(e.retryable()).isFalse();
                    assertThat(e.getMessage()).contains("토큰 발급 실패");
                });
    }

    @Test
    void reissuesTokenOnceOn401() {
        naver.expireNextToken = true;
        assertThat(client.findBySellerCode(creds, "X")).isEmpty();
        assertThat(naver.tokenCalls.get()).isEqualTo(2);
    }

    @Test
    void uploadsMultipartAndRegisters() throws Exception {
        Path f = Files.createTempFile("img", ".jpg");
        Files.write(f, new byte[] {1, 2, 3});
        assertThat(client.uploadImage(creds, f)).startsWith("https://shop-phinf.pstatic.net/");
        assertThat(client.registerProduct(creds, Map.of("originProduct", Map.of()))).containsEntry("originProductNo", 13700000001L);
    }

    @Test
    void mapsErrorsToRetryableOrInvalid() {
        naver.registerStatus = 400;
        assertThatThrownBy(() -> client.registerProduct(creds, Map.of()))
                .isInstanceOfSatisfying(ChannelException.class, e -> {
                    assertThat(e.retryable()).isFalse();
                    assertThat(e.getMessage()).contains("originAreaInfo: 원산지는 필수입니다");
                    assertThat(e.rawResponse()).containsKey("invalidInputs");
                });
        naver.registerStatus = 500;
        assertThatThrownBy(() -> client.registerProduct(creds, Map.of()))
                .isInstanceOfSatisfying(ChannelException.class, e -> assertThat(e.retryable()).isTrue());
    }
}
