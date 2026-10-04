package com.autoreg.job;

public enum JobStatus {
    QUEUED,
    RUNNING,
    SUCCEEDED,
    /** 타임아웃·5xx·429·LLM 한도. next_run_at 에 자동 재시도 */
    FAILED_RETRYABLE,
    /** 400 유효성 실패·재시도 소진. 사용자가 고친 뒤 재시도 */
    FAILED_INVALID,
    CANCELLED
}
