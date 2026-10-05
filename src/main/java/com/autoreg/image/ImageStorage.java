package com.autoreg.image;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

import javax.imageio.ImageIO;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 이미지 파일 저장. 판매자별로 폴더를 나눈다.
 * <pre>
 * {root}/{판매자코드}/{상품코드}/main_01.jpg
 * {root}/{판매자코드}/_thumbs/{상품코드}/main_01.jpg
 * {root}/{판매자코드}/_unmatched/원래파일명.jpg
 * </pre>
 * DB 에는 root 기준 상대경로만 저장한다.
 */
@Component
public class ImageStorage {

    public static final int THUMB_LONG_SIDE = 400;

    private final Path root;

    public ImageStorage(@Value("${autoreg.image-root}") String root) {
        this.root = Path.of(root).toAbsolutePath().normalize();
    }

    public Path root() {
        return root;
    }

    public record Stored(String path, String thumbPath, int width, int height, String sha256) {}

    /** 원본을 저장하고 썸네일을 만든다. 이미지로 읽히지 않으면 IllegalArgumentException */
    public Stored storeProductImage(String tenantCode, String productCode, String storedName, byte[] bytes) {
        BufferedImage img = read(bytes);
        String rel = tenantCode + "/" + productCode + "/" + storedName;
        String thumbRel = tenantCode + "/_thumbs/" + productCode + "/" + baseName(storedName) + ".jpg";
        write(resolve(rel), bytes);
        writeThumb(img, resolve(thumbRel));
        return new Stored(rel, thumbRel, img.getWidth(), img.getHeight(), sha256(bytes));
    }

    /** 파일 내용이 바뀐 뒤(워터마크 제거·되돌리기) 썸네일·크기·해시를 다시 만든다 */
    public Stored refresh(String relPath, String thumbRel) {
        byte[] bytes;
        try {
            bytes = Files.readAllBytes(resolve(relPath));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        BufferedImage img = read(bytes);
        writeThumb(img, resolve(thumbRel));
        return new Stored(relPath, thumbRel, img.getWidth(), img.getHeight(), sha256(bytes));
    }

    /** relPath 파일을 같은 폴더의 orig/ 로 옮기고 그 경로를 돌려준다 (이미 있으면 그대로) */
    public String moveToOriginals(String relPath) {
        Path src = resolve(relPath);
        String origRel = relPath.substring(0, relPath.lastIndexOf('/') + 1) + "orig/" + relPath.substring(relPath.lastIndexOf('/') + 1);
        Path dst = resolve(origRel);
        try {
            // 이미 있으면 그게 진짜 원본이다 (처리 도중 워커가 죽어 DB 에 기록이 안 된 경우). 덮어쓰면 처리본이 원본 자리를 차지한다
            if (Files.exists(dst)) {
                return origRel;
            }
            Files.createDirectories(dst.getParent());
            Files.move(src, dst);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return origRel;
    }

    public void moveBack(String origRel, String relPath) {
        try {
            Files.move(resolve(origRel), resolve(relPath), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public Path unmatchedDir(String tenantCode) {
        return resolve(tenantCode + "/_unmatched");
    }

    public void storeUnmatched(String tenantCode, String filename, byte[] bytes) {
        read(bytes); // 이미지가 아닌 파일은 받지 않는다
        write(unmatchedDir(tenantCode).resolve(safeName(filename)), bytes);
    }

    public void deleteQuietly(String relPath) {
        if (relPath == null) {
            return;
        }
        try {
            Files.deleteIfExists(resolve(relPath));
        } catch (IOException ignored) {
            // 파일이 이미 없거나 지울 수 없어도 DB 정리는 계속한다
        }
    }

    /** root 밖으로 나가는 경로는 거부한다 */
    public Path resolve(String relPath) {
        Path p = root.resolve(relPath).normalize();
        if (!p.startsWith(root)) {
            throw new IllegalArgumentException("잘못된 경로입니다");
        }
        return p;
    }

    /** 경로 구분자·제어문자를 지운 파일명 */
    public static String safeName(String filename) {
        String name = filename == null ? "" : filename.substring(Math.max(filename.lastIndexOf('/'), filename.lastIndexOf('\\')) + 1);
        name = name.replaceAll("[\\p{Cntrl}]", "").trim();
        if (name.isEmpty() || name.equals(".") || name.equals("..")) {
            throw new IllegalArgumentException("파일명이 비어 있습니다");
        }
        return name.length() > 150 ? name.substring(name.length() - 150) : name;
    }

    static BufferedImage read(byte[] bytes) {
        try {
            BufferedImage img = ImageIO.read(new ByteArrayInputStream(bytes));
            if (img == null) {
                throw new IllegalArgumentException("이미지 파일로 읽을 수 없습니다 (jpg, png, webp 만 가능)");
            }
            return img;
        } catch (IOException e) {
            throw new IllegalArgumentException("이미지 파일로 읽을 수 없습니다", e);
        }
    }

    private static void writeThumb(BufferedImage src, Path target) {
        double scale = Math.min(1.0, (double) THUMB_LONG_SIDE / Math.max(src.getWidth(), src.getHeight()));
        int w = Math.max(1, (int) Math.round(src.getWidth() * scale));
        int h = Math.max(1, (int) Math.round(src.getHeight() * scale));
        BufferedImage thumb = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = thumb.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.setColor(java.awt.Color.WHITE); // 투명 PNG 배경
            g.fillRect(0, 0, w, h);
            g.drawImage(src, 0, 0, w, h, null);
        } finally {
            g.dispose();
        }
        try {
            Files.createDirectories(target.getParent());
            Path tmp = Files.createTempFile(target.getParent(), ".thumb", ".tmp");
            ImageIO.write(thumb, "jpg", tmp.toFile());
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void write(Path target, byte[] bytes) {
        try {
            Files.createDirectories(target.getParent());
            Path tmp = Files.createTempFile(target.getParent(), ".up", ".tmp");
            Files.write(tmp, bytes);
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String baseName(String name) {
        int dot = name.lastIndexOf('.');
        return dot < 0 ? name : name.substring(0, dot);
    }

    static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
