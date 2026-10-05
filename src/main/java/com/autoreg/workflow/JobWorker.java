package com.autoreg.workflow;

import java.time.Duration;
import java.util.Optional;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.autoreg.job.Job;
import com.autoreg.job.JobException;
import com.autoreg.job.JobService;
import com.autoreg.job.JobStatus;
import com.autoreg.job.JobType;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * worker 프로파일에서만 뜬다. 같은 이미지를 api/worker 두 컨테이너로 띄워,
 * 등록 작업이 길어져도 대시보드 응답이 느려지지 않게 한다.
 */
@Slf4j
@Component
@Profile("worker")
@RequiredArgsConstructor
public class JobWorker {

    private final JobService jobs;
    private final GenerateJobHandler generate;
    private final RegisterJobHandler register;
    private final ExtractJobHandler extract;

    @EventListener(ApplicationReadyEvent.class)
    public void recover() {
        int n = jobs.recoverStale(Duration.ofMinutes(10));
        if (n > 0) {
            log.warn("recovered {} stale RUNNING jobs", n);
        }
    }

    @Scheduled(fixedDelayString = "${autoreg.worker.poll-ms:2000}")
    public void poll() {
        // 한 번 깨어나면 실행할 작업이 없을 때까지 처리한다
        for (int i = 0; i < 50; i++) {
            Optional<Long> next = jobs.claimNext();
            if (next.isEmpty()) {
                return;
            }
            runOne(next.get());
        }
    }

    public void runOne(Long jobId) {
        Job job = jobs.get(jobId);
        try {
            switch (job.getType()) {
                case GENERATE -> generate.handle(job);
                case REGISTER -> register.handle(job);
                case EXTRACT -> extract.handle(job);
            }
        } catch (JobException e) {
            failed(job, e);
        } catch (RuntimeException e) {
            log.error("job {} crashed", jobId, e);
            failed(job, JobException.retryable("예상치 못한 오류: " + e.getClass().getSimpleName() + ": " + e.getMessage(), e));
        }
    }

    private void failed(Job job, JobException e) {
        JobStatus result = jobs.fail(job.getId(), e);
        if (job.getType() == JobType.REGISTER) {
            register.onFailed(job, result);
        } else if (result == JobStatus.FAILED_INVALID) {
            generate.onGaveUp(job, e.getMessage());
        }
    }
}
