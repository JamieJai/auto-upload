package com.autoreg.image;

import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.autoreg.product.ImageSlot;

/**
 * 업로드 파일명 규칙: {상품코드}_{슬롯}[_{순번}].{jpg|jpeg|png|webp}
 * <pre>
 * SS2609001_main.jpg       → SS2609001, main, 1
 * SS2609001_detail_01.jpg  → SS2609001, detail, 1
 * </pre>
 * 규칙에 맞지 않으면 empty 를 돌려주고, 호출자는 _unmatched 로 보낸다.
 */
public record ImageFilename(String productCode, ImageSlot slot, int seq, String extension) {

    private static final Pattern RULE = Pattern.compile(
            "^(?<code>[A-Za-z0-9-]+)_(?<slot>main|sub|detail|size)(?:_(?<seq>\\d{1,3}))?\\.(?<ext>jpe?g|png|webp)$",
            Pattern.CASE_INSENSITIVE);

    public static Optional<ImageFilename> parse(String filename) {
        if (filename == null) {
            return Optional.empty();
        }
        // 브라우저가 경로를 붙여 보내는 경우가 있다
        String name = filename.substring(Math.max(filename.lastIndexOf('/'), filename.lastIndexOf('\\')) + 1).trim();
        Matcher m = RULE.matcher(name);
        if (!m.matches()) {
            return Optional.empty();
        }
        int seq = m.group("seq") == null ? 1 : Integer.parseInt(m.group("seq"));
        if (seq < 1) {
            return Optional.empty();
        }
        ImageSlot slot = ImageSlot.of(m.group("slot")).orElseThrow();
        String ext = m.group("ext").toLowerCase(Locale.ROOT).replace("jpeg", "jpg");
        return Optional.of(new ImageFilename(m.group("code"), slot, seq, ext));
    }

    /** 저장 파일명: main_01.jpg */
    public String storedName() {
        return String.format("%s_%02d.%s", slot.value(), seq, extension);
    }
}
