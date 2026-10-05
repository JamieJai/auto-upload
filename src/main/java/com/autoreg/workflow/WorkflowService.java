package com.autoreg.workflow;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.autoreg.channel.ChannelAccount;
import com.autoreg.channel.ChannelAccountRepository;
import com.autoreg.channel.ChannelListing;
import com.autoreg.channel.ChannelListingRepository;
import com.autoreg.channel.ListingStatus;
import com.autoreg.common.ConflictException;
import com.autoreg.common.NotFoundException;
import com.autoreg.common.ValidationFailedException;
import com.autoreg.generation.CopyGenerator;
import com.autoreg.job.JobService;
import com.autoreg.job.JobType;
import com.autoreg.product.Product;
import com.autoreg.product.ProductDtos.ProductResponse;
import com.autoreg.product.ProductRepository;
import com.autoreg.product.ProductStatus;
import com.autoreg.product.TextField;
import com.autoreg.product.validation.ProductValidator;
import com.autoreg.product.validation.ValidationIssue;
import com.autoreg.product.validation.ValidationPhase;
import com.autoreg.tenant.TenantService;

import lombok.RequiredArgsConstructor;

/**
 * 상품 상태 전이.
 * <pre>
 * DRAFT/NEEDS_INPUT --submit--> (검증 실패) NEEDS_INPUT
 *                               (빈 문구 있음) GENERATING --워커--> PENDING_APPROVAL
 *                               (문구 다 있음) PENDING_APPROVAL
 * PENDING_APPROVAL --approve--> APPROVED (+ 채널마다 REGISTER 작업)
 *                  --reject---> NEEDS_INPUT
 * APPROVED --reopen--> NEEDS_INPUT,  * --cancel--> CANCELLED --reopen--> DRAFT
 * </pre>
 */
@Service
@RequiredArgsConstructor
public class WorkflowService {

    private final ProductRepository products;
    private final ChannelAccountRepository accounts;
    private final ChannelListingRepository listings;
    private final TenantService tenants;
    private final ProductValidator validator;
    private final CopyGenerator generator;
    private final JobService jobs;

    @Transactional(noRollbackFor = ValidationFailedException.class)
    public ProductResponse submit(Long tenantId, Long id) {
        Product p = find(tenantId, id);
        require(p, EnumSet.of(ProductStatus.DRAFT, ProductStatus.NEEDS_INPUT), "제출");
        List<ValidationIssue> issues = validator.validate(p, ValidationPhase.INPUT, tenants.get(tenantId).styleProfile());
        if (!issues.isEmpty()) {
            p.setStatus(ProductStatus.NEEDS_INPUT);
            throw new ValidationFailedException("필수값이 비어 있어 보완이 필요합니다", issues);
        }
        p.setReviewNote(null);
        if (CopyGenerator.emptyFields(p).isEmpty()) {
            p.setStatus(ProductStatus.PENDING_APPROVAL);
        } else {
            p.setStatus(ProductStatus.GENERATING);
            jobs.enqueue(tenantId, id, JobType.GENERATE, null);
        }
        return ProductResponse.of(p);
    }

    @Transactional
    public ProductResponse approve(Long tenantId, Long id) {
        Product p = find(tenantId, id);
        require(p, EnumSet.of(ProductStatus.PENDING_APPROVAL), "승인");
        List<ValidationIssue> issues = validator.validate(p, ValidationPhase.READY, tenants.get(tenantId).styleProfile());
        if (!issues.isEmpty()) {
            throw new ValidationFailedException("등록 전 확인이 필요한 항목이 있습니다", issues);
        }
        List<ChannelAccount> active = accounts.findByTenantIdOrderByChannelAsc(tenantId).stream()
                .filter(ChannelAccount::isActive).toList();
        if (active.isEmpty()) {
            throw new ConflictException("이 판매자에 활성 채널 계정이 없습니다. 판매자 관리에서 먼저 추가하세요");
        }
        for (ChannelAccount a : active) {
            // 같은 상품·계정은 같은 행과 멱등성 키를 계속 쓴다
            ChannelListing l = listings.findByProductIdAndChannelAccountId(id, a.getId()).orElseGet(() -> {
                ChannelListing n = new ChannelListing();
                n.setTenantId(tenantId);
                n.setProductId(id);
                n.setChannelAccountId(a.getId());
                return listings.save(n);
            });
            if (l.getStatus() == ListingStatus.COMPLETED) {
                continue; // 수정 등록은 아직 없다
            }
            l.setStatus(ListingStatus.PENDING);
            jobs.enqueue(tenantId, id, JobType.REGISTER, l.getId());
        }
        p.setStatus(ProductStatus.APPROVED);
        return ProductResponse.of(p);
    }

    @Transactional
    public ProductResponse reject(Long tenantId, Long id, String note) {
        Product p = find(tenantId, id);
        require(p, EnumSet.of(ProductStatus.PENDING_APPROVAL), "반려");
        p.setStatus(ProductStatus.NEEDS_INPUT);
        p.setReviewNote(note == null || note.isBlank() ? "반려됨" : note.strip());
        return ProductResponse.of(p);
    }

    /** 승인·취소된 상품을 다시 고칠 수 있게 연다. 진행 중인 등록이 있으면 막는다 */
    @Transactional
    public ProductResponse reopen(Long tenantId, Long id) {
        Product p = find(tenantId, id);
        require(p, EnumSet.of(ProductStatus.APPROVED, ProductStatus.CANCELLED), "다시 열기");
        if (jobs.hasRunning(id)) {
            throw new ConflictException("실행 중인 작업이 끝난 뒤 다시 시도하세요");
        }
        jobs.cancelOpen(id, "상품을 다시 열어 작업 취소");
        stopListings(id);
        p.setStatus(p.getStatus() == ProductStatus.CANCELLED ? ProductStatus.DRAFT : ProductStatus.NEEDS_INPUT);
        return ProductResponse.of(p);
    }

    @Transactional
    public ProductResponse cancel(Long tenantId, Long id) {
        Product p = find(tenantId, id);
        if (p.getStatus() == ProductStatus.CANCELLED) {
            return ProductResponse.of(p);
        }
        if (jobs.hasRunning(id)) {
            throw new ConflictException("실행 중인 작업이 끝난 뒤 다시 시도하세요");
        }
        jobs.cancelOpen(id, "상품 취소");
        stopListings(id);
        p.setStatus(ProductStatus.CANCELLED);
        return ProductResponse.of(p);
    }

    /** 대시보드의 "AI로 생성" 버튼. 그 필드만 바로 생성해 저장한다 (사람이 고치면 출처가 MANUAL 로 바뀐다) */
    @Transactional
    public ProductResponse generateField(Long tenantId, Long id, TextField field) {
        Product p = find(tenantId, id);
        if (!p.getStatus().editable()) {
            throw new ConflictException("현재 상태(" + p.getStatus() + ")에서는 수정할 수 없습니다");
        }
        generator.generate(p, tenants.get(tenantId), Set.of(field));
        return ProductResponse.of(p);
    }

    private void stopListings(Long productId) {
        listings.findByProductId(productId).stream()
                .filter(l -> l.getStatus() != ListingStatus.COMPLETED)
                .forEach(l -> l.setStatus(ListingStatus.CANCELLED));
    }

    private Product find(Long tenantId, Long id) {
        return products.findByIdAndTenantId(id, tenantId).orElseThrow(() -> new NotFoundException("product", id));
    }

    private static void require(Product p, Set<ProductStatus> allowed, String action) {
        if (!allowed.contains(p.getStatus())) {
            throw new ConflictException("현재 상태(" + p.getStatus() + ")에서는 " + action + "할 수 없습니다");
        }
    }
}
