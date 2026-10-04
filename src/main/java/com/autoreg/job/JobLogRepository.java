package com.autoreg.job;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface JobLogRepository extends JpaRepository<JobLog, Long> {

    List<JobLog> findByJobIdOrderByIdAsc(Long jobId);
}
