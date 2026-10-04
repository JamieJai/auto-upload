package com.autoreg.workflow;

import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.autoreg.job.JobStatus;
import com.autoreg.workflow.OpsDtos.ApprovalItem;
import com.autoreg.workflow.OpsDtos.Dashboard;
import com.autoreg.workflow.OpsDtos.JobDetail;
import com.autoreg.workflow.OpsDtos.JobSummary;

import lombok.RequiredArgsConstructor;

/** tenantId 를 빼면 전체 판매자를 섞어 본다 (작업 현황·승인 대기는 그렇게 보는 게 편하다) */
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class OpsController {

    private final OpsService service;

    @GetMapping("/dashboard")
    public Dashboard dashboard(@RequestParam(required = false) Long tenantId) {
        return service.dashboard(tenantId);
    }

    @GetMapping("/jobs")
    public Page<JobSummary> jobs(@RequestParam(required = false) Long tenantId,
            @RequestParam(required = false) List<JobStatus> status,
            @PageableDefault(size = 50, sort = "updatedAt", direction = Sort.Direction.DESC) Pageable pageable) {
        return service.jobs(tenantId, OpsService.parse(status), pageable);
    }

    @GetMapping("/jobs/{id}")
    public JobDetail job(@PathVariable Long id) {
        return service.job(id);
    }

    @PostMapping("/jobs/{id}/retry")
    public JobDetail retry(@PathVariable Long id) {
        return service.retry(id);
    }

    @GetMapping("/approvals")
    public Page<ApprovalItem> approvals(@RequestParam(required = false) Long tenantId,
            @PageableDefault(size = 50, sort = "updatedAt", direction = Sort.Direction.ASC) Pageable pageable) {
        return service.approvals(tenantId, pageable);
    }
}
