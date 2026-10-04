package com.autoreg.channel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import org.junit.jupiter.api.Test;

class CredentialCipherTest {

    static final String KEY = Base64.getEncoder().encodeToString(new byte[32]);

    @Test
    void roundTripsWithRandomIv() {
        var cipher = new CredentialCipher(KEY);
        byte[] plain = "{\"clientSecret\":\"s3cr3t\"}".getBytes(StandardCharsets.UTF_8);
        byte[] a = cipher.encrypt(plain);
        byte[] b = cipher.encrypt(plain);
        assertThat(a).isNotEqualTo(b);
        assertThat(new String(a, StandardCharsets.ISO_8859_1)).doesNotContain("s3cr3t");
        assertThat(cipher.decrypt(a)).isEqualTo(plain);
    }

    @Test
    void tamperedDataFails() {
        var cipher = new CredentialCipher(KEY);
        byte[] enc = cipher.encrypt("x".getBytes(StandardCharsets.UTF_8));
        enc[enc.length - 1] ^= 1;
        assertThatThrownBy(() -> cipher.decrypt(enc)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void requiresKey() {
        assertThatThrownBy(() -> new CredentialCipher("")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new CredentialCipher(Base64.getEncoder().encodeToString(new byte[16])))
                .isInstanceOf(IllegalStateException.class);
    }
}
