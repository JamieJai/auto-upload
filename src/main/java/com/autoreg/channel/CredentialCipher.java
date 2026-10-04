package com.autoreg.channel;

import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 채널 인증정보 암호화. AES-256-GCM, 저장 형식은 [12바이트 IV][암호문+태그].
 * 마스터 키는 환경변수 AUTOREG_MASTER_KEY (base64 32바이트)로만 받는다. 레포에 커밋하지 않는다.
 */
@Component
public class CredentialCipher {

    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;

    private final SecretKey key;
    private final SecureRandom random = new SecureRandom();

    public CredentialCipher(@Value("${autoreg.master-key:}") String base64Key) {
        if (base64Key == null || base64Key.isBlank()) {
            throw new IllegalStateException("AUTOREG_MASTER_KEY 가 설정되지 않았습니다 (base64 32바이트)");
        }
        byte[] raw = Base64.getDecoder().decode(base64Key.trim());
        if (raw.length != 32) {
            throw new IllegalStateException("AUTOREG_MASTER_KEY 는 32바이트여야 합니다 (현재 " + raw.length + ")");
        }
        this.key = new SecretKeySpec(raw, "AES");
    }

    public byte[] encrypt(byte[] plain) {
        try {
            byte[] iv = new byte[IV_BYTES];
            random.nextBytes(iv);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] body = c.doFinal(plain);
            return ByteBuffer.allocate(IV_BYTES + body.length).put(iv).put(body).array();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("암호화 실패", e);
        }
    }

    public byte[] decrypt(byte[] stored) {
        try {
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, stored, 0, IV_BYTES));
            return c.doFinal(stored, IV_BYTES, stored.length - IV_BYTES);
        } catch (GeneralSecurityException e) {
            // 키가 바뀌었거나 데이터가 손상됨. 원인 메시지에 데이터는 싣지 않는다
            throw new IllegalStateException("인증정보 복호화 실패 (마스터 키 확인 필요)");
        }
    }
}
