package com.autoreg.workflow;

import java.util.Set;

import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import com.autoreg.generation.CopyGenerator;
import com.autoreg.generation.GeneratedCopyException;
import com.autoreg.job.Job;
import com.autoreg.job.JobException;
import com.autoreg.job.JobService;
import com.autoreg.llm.LlmException;
import com.autoreg.product.Product;
import com.autoreg.product.ProductRepository;
import com.autoreg.product.ProductStatus;
import com.autoreg.product.TextField;
import com.autoreg.tenant.TenantService;

import lombok.RequiredArgsConstructor;

/** 비어 있는 문구만 생성해 채우고 승인 대기로 넘긴다 */
@Component
@RequiredArgsConstructor
public class GenerateJobHandler {

    private final ProductRepository products;
    private final TenantService tenants;
    private final CopyGenerator generator;
    private final JobService jobs;
    private final TransactionTemplate tx;

    public void handle(Job job) {
        jobs.step(job.getId(), "GENERATE", "빈 문구 생성 시작");
        String done = tx.execute(s -> {
            Product p = products.findByIdAndTenantId(job.getProductId(), job.getTenantId())
                    .orElseThrow(() -> JobException.invalid("상품이 없습니다"));
            if (p.getStatus() != ProductStatus.GENERATING) {
                return "상품 상태가 " + p.getStatus() + " 로 바뀌어 생성을 건너뜀";
            }
            Set<TextField> fields = CopyGenerator.emptyFields(p);
            try {
                generator.generate(p, tenants.get(job.getTenantId()), fields);
            } catch (LlmException e) {
                throw e.retryable() ? JobException.retryable(e.getMessage(), e.retryAfter()) : JobException.invalid(e.getMessage());
            } catch (GeneratedCopyException e) {
                throw JobException.retryable(e.getMessage());
            }
            p.setStatus(ProductStatus.PENDING_APPROVAL);
            return fields.isEmpty() ? "생성할 빈 문구 없음 → 승인 대기" : fields + " 생성 → 승인 대기";
        });
        jobs.succeed(job.getId(), done);
    }

    /** 재시도까지 모두 실패하면 상품을 보완 필요로 돌린다 */
    public void onGaveUp(Job job, String error) {
        tx.executeWithoutResult(s -> products.findByIdAndTenantId(job.getProductId(), job.getTenantId()).ifPresent(p -> {
            if (p.getStatus() == ProductStatus.GENERATING) {
                p.setStatus(ProductStatus.NEEDS_INPUT);
                p.setReviewNote("문구 자동 생성 실패: " + error + " — 직접 입력하거나 다시 제출하세요");
            }
        }));
    }
}
