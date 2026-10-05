package com.autoreg.intake;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.autoreg.common.NotFoundException;

import lombok.RequiredArgsConstructor;

/** 브라우저 확장용 토큰. 원문은 발급 응답에서 한 번만 보여 주고, DB 에는 SHA-256 만 둔다 */
@Service
@RequiredArgsConstructor
public class IntakeTokenService {

    private final IntakeTokenRepository tokens;
    private final SecureRandom random = new SecureRandom();

    public record Issued(Long id, String name, String token) {}

    @Transactional
    public Issued issue(String name) {
        byte[] raw = new byte[32];
        random.nextBytes(raw);
        String token = "ar_" + Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        IntakeToken t = new IntakeToken();
        t.setName(name == null || name.isBlank() ? "브라우저 확장" : name.strip());
        t.setTokenHash(hash(token));
        tokens.save(t);
        return new Issued(t.getId(), t.getName(), token);
    }

    public List<IntakeToken> list() {
        return tokens.findAllByOrderByIdDesc();
    }

    @Transactional
    public void revoke(Long id) {
        IntakeToken t = tokens.findById(id).orElseThrow(() -> new NotFoundException("intake token", id));
        if (t.getRevokedAt() == null) {
            t.setRevokedAt(OffsetDateTime.now());
        }
    }

    /** 유효하면 토큰 이름. 마지막 사용 시각을 남긴다 */
    @Transactional
    public Optional<String> authenticate(String token) {
        if (token == null || !token.startsWith("ar_")) {
            return Optional.empty();
        }
        return tokens.findByTokenHashAndRevokedAtIsNull(hash(token)).map(t -> {
            t.setLastUsedAt(OffsetDateTime.now());
            return t.getName();
        });
    }

    static String hash(String token) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
