package com.autoreg.workflow;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.autoreg.channel.ChannelAccountRepository;
import com.autoreg.channel.ChannelListingRepository;
import com.autoreg.common.ConflictException;
import com.autoreg.job.Job;
import com.autoreg.job.JobRepository;
import com.autoreg.job.JobService;
import com.autoreg.job.JobStatus;
import com.autoreg.job.JobType;
import com.autoreg.product.Product;
import com.autoreg.product.ProductRepository;
import com.autoreg.product.ProductStatus;
import com.autoreg.tenant.Tenant;
import com.autoreg.tenant.TenantRepository;
import com.autoreg.workflow.OpsDtos.ApprovalItem;
import com.autoreg.workflow.OpsDtos.Dashboard;
import com.autoreg.workflow.OpsDtos.JobDetail;
import com.autoreg.workflow.OpsDtos.JobLogEntry;
import com.autoreg.workflow.OpsDtos.JobSummary;
import com.autoreg.workflow.OpsDtos.ListingView;
import com.autoreg.workflow.OpsDtos.Today;

import lombok.RequiredArgsConstructor;

/** 작업 현황·작업 상세·승인 대기 화면용 조회. tenantId 가 null 이면 전체 판매자 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class OpsService {

    static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private final JobRepository jobRepo;
    private final JobService jobs;
    private final ProductRepository products;
    private final TenantRepository tenants;
    private final ChannelListingRepository listings;
    private final ChannelAccountRepository accounts;

    public Page<JobSummary> jobs(Long tenantId, Set<JobStatus> statuses, Pageable pageable) {
        Page<Job> page;
        if (statuses == null || statuses.isEmpty()) {
            page = tenantId == null ? jobRepo.findAll(pageable) : jobRepo.findByTenantId(tenantId, pageable);
        } else {
            page = tenantId == null ? jobRepo.findByStatusIn(statuses, pageable)
                    : jobRepo.findByTenantIdAndStatusIn(tenantId, statuses, pageable);
        }
        List<JobSummary> mapped = summaries(page.getContent());
        return page.map(j -> mapped.stream().filter(s -> s.id().equals(j.getId())).findFirst().orElseThrow());
    }

    public JobDetail job(Long id) {
        Job j = jobs.get(id);
        List<JobLogEntry> logs = jobs.logs(id).stream()
                .map(l -> new JobLogEntry(l.getId(), l.getStep(), l.getLevel().name(), l.getMessage(), l.getCreatedAt()))
                .toList();
        ListingView listing = j.getChannelListingId() == null ? null
                : listings.findById(j.getChannelListingId()).map(l -> {
                    var a = accounts.findById(l.getChannelAccountId()).orElseThrow();
                    return new ListingView(l.getId(), a.getChannel(), a.getDisplayName(), l.getStatus(),
                            l.getChannelProductNo(), l.getLastResponse(), l.getRegisteredAt());
                }).orElse(null);
        return new JobDetail(summaries(List.of(j)).get(0), logs, listing);
    }

    @Transactional
    public JobDetail retry(Long id) {
        Job j = jobs.get(id);
        if (j.getType() == JobType.GENERATE) {
            throw new ConflictException("문구 생성은 상품을 다시 제출해서 재시도하세요");
        }
        if (j.getType() == JobType.EXTRACT || j.getType() == JobType.WATERMARK) {
            jobs.retry(id);
            return job(id);
        }
        Product p = products.findById(j.getProductId()).orElseThrow();
        if (p.getStatus() != ProductStatus.APPROVED) {
            throw new ConflictException("승인된 상품만 다시 등록할 수 있습니다 (현재 " + p.getStatus() + ")");
        }
        jobs.retry(id);
        // 이미 등록된 행(상품번호 있음)은 되돌리지 않는다. 그대로 두면 워커가 등록 없이 재조회 검증만 한다
        listings.findById(j.getChannelListingId())
                .filter(l -> l.getStatus() != com.autoreg.channel.ListingStatus.COMPLETED)
                .ifPresent(l -> l.setStatus(com.autoreg.channel.ListingStatus.PENDING));
        return job(id);
    }

    public Dashboard dashboard(Long tenantId) {
        OffsetDateTime since = LocalDate.now(KST).atStartOfDay(KST).toOffsetDateTime();
        Map<JobStatus, Long> byStatus = new HashMap<>();
        for (Object[] row : jobRepo.countByStatus(tenantId)) {
            byStatus.put((JobStatus) row[0], (Long) row[1]);
        }
        long processing = byStatus.getOrDefault(JobStatus.QUEUED, 0L) + byStatus.getOrDefault(JobStatus.RUNNING, 0L)
                + byStatus.getOrDefault(JobStatus.FAILED_RETRYABLE, 0L);
        long pending = tenantId == null ? products.countByStatus(ProductStatus.PENDING_APPROVAL)
                : products.countByTenantIdAndStatus(tenantId, ProductStatus.PENDING_APPROVAL);
        Today today = new Today(jobRepo.countCreatedSince(tenantId, since), jobRepo.countRegisteredSince(tenantId, since),
                processing, byStatus.getOrDefault(JobStatus.FAILED_INVALID, 0L), pending);
        Pageable recent = PageRequest.of(0, 20, Sort.by(Sort.Direction.DESC, "updatedAt"));
        return new Dashboard(today, jobs(tenantId, null, recent).getContent());
    }

    public Page<ApprovalItem> approvals(Long tenantId, Pageable pageable) {
        Page<Product> page = tenantId == null ? products.findByStatus(ProductStatus.PENDING_APPROVAL, pageable)
                : products.findByTenantIdAndStatus(tenantId, ProductStatus.PENDING_APPROVAL, pageable);
        Map<Long, Tenant> ts = tenantMap(page.getContent().stream().map(Product::getTenantId).collect(Collectors.toSet()));
        return page.map(p -> {
            Tenant t = ts.get(p.getTenantId());
            return new ApprovalItem(p.getId(), p.getTenantId(), t.getCode(), t.getName(), p.getCode(), p.getName(),
                    p.getCategory(), p.getSalePrice(), p.getStatus(), p.getUpdatedAt());
        });
    }

    private List<JobSummary> summaries(List<Job> list) {
        Map<Long, Product> ps = products.findAllById(list.stream().map(Job::getProductId).collect(Collectors.toSet()))
                .stream().collect(Collectors.toMap(Product::getId, Function.identity()));
        Map<Long, Tenant> ts = tenantMap(list.stream().map(Job::getTenantId).collect(Collectors.toSet()));
        return list.stream().map(j -> {
            Product p = ps.get(j.getProductId());
            Tenant t = ts.get(j.getTenantId());
            return new JobSummary(j.getId(), j.getTenantId(), t == null ? null : t.getCode(), j.getProductId(),
                    p == null ? null : p.getCode(), p == null ? null : p.getName(), j.getType(), j.getStatus(),
                    j.getStep(), j.getAttempt(), j.getNextRunAt(), j.getLastError(), j.getCreatedAt(), j.getUpdatedAt());
        }).toList();
    }

    private Map<Long, Tenant> tenantMap(Set<Long> ids) {
        return tenants.findAllById(ids).stream().collect(Collectors.toMap(Tenant::getId, Function.identity()));
    }

    static Set<JobStatus> parse(List<JobStatus> in) {
        return in == null || in.isEmpty() ? EnumSet.noneOf(JobStatus.class) : EnumSet.copyOf(in);
    }
}
