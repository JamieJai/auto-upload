package com.autoreg.workflow;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import com.autoreg.channel.Channel;
import com.autoreg.channel.ListingStatus;
import com.autoreg.job.JobStatus;
import com.autoreg.job.JobType;
import com.autoreg.product.ProductStatus;

public final class OpsDtos {

    private OpsDtos() {}

    public record JobSummary(Long id, Long tenantId, String tenantCode, Long productId, String productCode,
            String productName, JobType type, JobStatus status, String step, int attempt, OffsetDateTime nextRunAt,
            String lastError, OffsetDateTime createdAt, OffsetDateTime updatedAt) {}

    public record JobLogEntry(Long id, String step, String level, String message, OffsetDateTime createdAt) {}

    public record ListingView(Long id, Channel channel, String accountName, ListingStatus status,
            String channelProductNo, Map<String, Object> lastResponse, OffsetDateTime registeredAt) {}

    public record JobDetail(JobSummary job, List<JobLogEntry> logs, ListingView listing) {}

    public record Today(long received, long completed, long processing, long failed, long pendingApproval) {}

    public record Dashboard(Today today, List<JobSummary> recent) {}

    public record ApprovalItem(Long productId, Long tenantId, String tenantCode, String tenantName, String code,
            String name, String category, Integer salePrice, ProductStatus status, OffsetDateTime updatedAt) {}
}
