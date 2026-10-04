package com.autoreg.excel;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.autoreg.excel.ExcelDtos.ImportResult;
import com.autoreg.excel.ExcelDtos.PreviewResponse;

import lombok.RequiredArgsConstructor;

@RestController
@RequiredArgsConstructor
public class ExcelController {

    private static final MediaType XLSX =
            MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");

    private final ExcelImportService service;

    @GetMapping("/api/excel/template")
    public ResponseEntity<byte[]> template() {
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename("상품등록_템플릿.xlsx", StandardCharsets.UTF_8).build().toString())
                .contentType(XLSX)
                .body(ExcelTemplate.build());
    }

    /** 1단계: 파싱·검증 결과만 돌려준다. 아무것도 저장하지 않는다 */
    @PostMapping(path = "/api/tenants/{tenantId}/excel/preview", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public PreviewResponse preview(@PathVariable Long tenantId, @RequestPart("file") MultipartFile file) {
        return service.preview(tenantId, bytes(file));
    }

    /** 2단계: 사용자가 미리보기를 확인한 뒤 같은 파일로 호출한다. 오류 없는 행만 상품을 만든다 */
    @PostMapping(path = "/api/tenants/{tenantId}/excel/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ImportResult importRows(@PathVariable Long tenantId, @RequestPart("file") MultipartFile file) {
        return service.importRows(tenantId, bytes(file));
    }

    private static byte[] bytes(MultipartFile f) {
        try {
            return f.getBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
