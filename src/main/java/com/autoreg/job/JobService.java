package com.autoreg.job;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.autoreg.common.ConflictException;
import com.autoreg.common.NotFoundException;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class JobService {

    /** 재시도 간격: 30초 → 5분 → 30분. 그다음 실패는 FAILED_INVALID */
    static final List<Duration> BACKOFF = List.of(Duration.ofSeconds(30), Duration.ofMinutes(5), Duration.ofMinutes(30));
    static final int MAX_ATTEMPTS = BACKOFF.size() + 1;

    /** 로그에 키·토큰이 남지 않게 가린다 */
    private static final Pattern SECRET = Pattern.compile(
            "(?i)(\"?(?:client_?secret|access_?token|refresh_?token|token|password|secret|authorization)\"?\\s*[:=]\\s*\"?)([^\",\\s}]+)");

    private final JobRepository jobs;
    private final JobLogRepository logs;
    private final JdbcTemplate jdbc;

    @Transactional
    public Job enqueue(Long tenantId, Long productId, JobType type, Long channelListingId) {
        Job j = new Job();
        j.setTenantId(tenantId);
        j.setProductId(productId);
        j.setType(type);
        j.setChannelListingId(channelListingId);
        j.setStep("QUEUED");
        Job saved = jobs.save(j);
        log(saved.getId(), "QUEUED", JobLog.Level.INFO, type + " 작업 생성", null);
        return saved;
    }

    /**
     * 실행할 작업 하나를 RUNNING 으로 바꾸고 id 를 돌려준다. 여러 워커가 동시에 돌아도 같은 작업을 잡지 않는다.
     */
    @Transactional
    public Optional<Long> claimNext() {
        List<Long> ids = jdbc.queryForList("""
                UPDATE job SET status = 'RUNNING', attempt = attempt + 1, updated_at = now()
                WHERE id = (
                    SELECT id FROM job
                    WHERE status IN ('QUEUED', 'FAILED_RETRYABLE') AND next_run_at <= now()
                    ORDER BY next_run_at, id
                    LIMIT 1
                    FOR UPDATE SKIP LOCKED)
                RETURNING id""", Long.class);
        return ids.stream().findFirst();
    }

    public Job get(Long id) {
        return jobs.findById(id).orElseThrow(() -> new NotFoundException("job", id));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void step(Long jobId, String step, String message) {
        Job j = get(jobId);
        j.setStep(step);
        log(jobId, step, JobLog.Level.INFO, message, null);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void succeed(Long jobId, String message) {
        Job j = get(jobId);
        j.setStatus(JobStatus.SUCCEEDED);
        j.setStep("DONE");
        j.setLastError(null);
        log(jobId, "DONE", JobLog.Level.INFO, message, null);
    }

    /** @return 최종 상태 (FAILED_RETRYABLE 또는 FAILED_INVALID) */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public JobStatus fail(Long jobId, JobException e) {
        Job j = get(jobId);
        String msg = mask(e.getMessage());
        j.setLastError(msg);
        if (e.retryable() && j.getAttempt() < MAX_ATTEMPTS) {
            Duration wait = BACKOFF.get(Math.min(j.getAttempt(), BACKOFF.size()) - 1);
            if (e.retryAfter() != null && e.retryAfter().compareTo(wait) > 0) {
                wait = e.retryAfter();
            }
            j.setStatus(JobStatus.FAILED_RETRYABLE);
            j.setNextRunAt(OffsetDateTime.now().plus(wait));
            log(jobId, j.getStep(), JobLog.Level.WARN, msg + " → " + wait.toSeconds() + "초 후 재시도 (" + j.getAttempt()
                    + "/" + MAX_ATTEMPTS + ")", null);
        } else {
            j.setStatus(JobStatus.FAILED_INVALID);
            log(jobId, j.getStep(), JobLog.Level.ERROR,
                    e.retryable() ? msg + " → 재시도 " + (MAX_ATTEMPTS - 1) + "회 소진" : msg, null);
        }
        return j.getStatus();
    }

    /** 사용자가 고친 뒤 다시 돌린다. 시도 횟수를 0 으로 되돌린다 */
    @Transactional
    public Job retry(Long jobId) {
        Job j = get(jobId);
        if (j.getStatus() != JobStatus.FAILED_INVALID && j.getStatus() != JobStatus.FAILED_RETRYABLE) {
            throw new ConflictException("실패한 작업만 재시도할 수 있습니다 (현재 " + j.getStatus() + ")");
        }
        j.setStatus(JobStatus.QUEUED);
        j.setAttempt(0);
        j.setNextRunAt(OffsetDateTime.now());
        log(jobId, j.getStep(), JobLog.Level.INFO, "수동 재시도", null);
        return j;
    }

    @Transactional
    public void cancelOpen(Long productId, String reason) {
        for (Job j : jobs.findByProductIdAndStatusIn(productId,
                EnumSet.of(JobStatus.QUEUED, JobStatus.FAILED_RETRYABLE, JobStatus.FAILED_INVALID))) {
            j.setStatus(JobStatus.CANCELLED);
            log(j.getId(), j.getStep(), JobLog.Level.INFO, reason, null);
        }
    }

    public boolean hasRunning(Long productId) {
        return !jobs.findByProductIdAndStatusIn(productId, EnumSet.of(JobStatus.RUNNING)).isEmpty();
    }

    /** 워커가 죽어서 RUNNING 으로 남은 작업을 재시도 대기로 돌린다 */
    @Transactional
    public int recoverStale(Duration olderThan) {
        List<Job> stale = jobs.findByStatusAndUpdatedAtBefore(JobStatus.RUNNING, OffsetDateTime.now().minus(olderThan));
        for (Job j : stale) {
            j.setStatus(JobStatus.FAILED_RETRYABLE);
            j.setNextRunAt(OffsetDateTime.now());
            log(j.getId(), j.getStep(), JobLog.Level.WARN, "워커 중단으로 RUNNING 에 남아 있던 작업을 재시도 대기로 돌림", null);
        }
        return stale.size();
    }

    public List<JobLog> logs(Long jobId) {
        return logs.findByJobIdOrderByIdAsc(jobId);
    }

    @Transactional
    public void log(Long jobId, String step, JobLog.Level level, String message, Map<String, Object> payload) {
        JobLog l = new JobLog();
        l.setJobId(jobId);
        l.setStep(step);
        l.setLevel(level);
        l.setMessage(mask(message));
        l.setPayload(payload);
        logs.save(l);
    }

    static String mask(String s) {
        return s == null ? null : SECRET.matcher(s).replaceAll("$1***");
    }
}
