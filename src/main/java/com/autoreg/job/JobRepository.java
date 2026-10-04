package com.autoreg.job;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface JobRepository extends JpaRepository<Job, Long> {

    Page<Job> findByTenantId(Long tenantId, Pageable pageable);

    Page<Job> findByStatusIn(Collection<JobStatus> statuses, Pageable pageable);

    Page<Job> findByTenantIdAndStatusIn(Long tenantId, Collection<JobStatus> statuses, Pageable pageable);

    List<Job> findByProductIdAndStatusIn(Long productId, Collection<JobStatus> statuses);

    List<Job> findByStatusAndUpdatedAtBefore(JobStatus status, OffsetDateTime before);

    @Query("select j.status, count(j) from Job j where (:tenantId is null or j.tenantId = :tenantId) group by j.status")
    List<Object[]> countByStatus(Long tenantId);

    @Query("select count(j) from Job j where (:tenantId is null or j.tenantId = :tenantId) and j.createdAt >= :since")
    long countCreatedSince(Long tenantId, OffsetDateTime since);

    @Query("""
            select count(j) from Job j where (:tenantId is null or j.tenantId = :tenantId)
            and j.status = com.autoreg.job.JobStatus.SUCCEEDED and j.type = com.autoreg.job.JobType.REGISTER
            and j.updatedAt >= :since""")
    long countRegisteredSince(Long tenantId, OffsetDateTime since);
}
