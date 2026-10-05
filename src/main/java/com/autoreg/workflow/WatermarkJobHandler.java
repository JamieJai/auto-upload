package com.autoreg.workflow;

import org.springframework.stereotype.Component;

import com.autoreg.job.Job;
import com.autoreg.job.JobException;
import com.autoreg.job.JobService;
import com.autoreg.watermark.WatermarkService;

import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class WatermarkJobHandler {

    private final WatermarkService watermarks;
    private final JobService jobs;

    public void handle(Job job) {
        String template = job.getParams() == null ? null : (String) job.getParams().get("template");
        if (template == null) {
            throw JobException.invalid("템플릿이 지정되지 않았습니다");
        }
        boolean redo = Boolean.TRUE.equals(job.getParams().get("redo"));
        jobs.step(job.getId(), "WATERMARK", "워터마크 제거 (" + template + (redo ? ", 원본에서 다시" : "") + ")");
        WatermarkService.Applied r;
        try {
            r = watermarks.apply(job.getTenantId(), job.getProductId(), template, redo);
        } catch (IllegalArgumentException | com.autoreg.common.ConflictException e) {
            throw JobException.invalid(e.getMessage());
        } catch (IllegalStateException e) {
            throw JobException.retryable(e.getMessage());
        }
        if (!r.errors().isEmpty() && r.processed() == 0) {
            throw JobException.retryable("모두 실패: " + String.join("; ", r.errors()));
        }
        jobs.succeed(job.getId(), "워터마크 제거 " + r.processed() + "장, 건너뜀 " + r.skipped() + "장"
                + (r.errors().isEmpty() ? "" : " / 실패: " + String.join("; ", r.errors())));
    }
}
