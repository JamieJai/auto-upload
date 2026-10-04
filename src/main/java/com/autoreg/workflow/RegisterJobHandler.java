package com.autoreg.workflow;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import com.autoreg.channel.CategoryMappingRepository;
import com.autoreg.channel.ChannelAccount;
import com.autoreg.channel.ChannelAccountRepository;
import com.autoreg.channel.ChannelAccountService;
import com.autoreg.channel.ChannelImage;
import com.autoreg.channel.ChannelImageRepository;
import com.autoreg.channel.ChannelListing;
import com.autoreg.channel.ChannelListingRepository;
import com.autoreg.channel.ListingStatus;
import com.autoreg.channel.adapter.ChannelAdapter;
import com.autoreg.channel.adapter.ChannelAdapter.UploadedImage;
import com.autoreg.channel.adapter.ChannelException;
import com.autoreg.channel.adapter.RegistrationContext;
import com.autoreg.image.ImageStorage;
import com.autoreg.job.Job;
import com.autoreg.job.JobException;
import com.autoreg.job.JobService;
import com.autoreg.job.JobStatus;
import com.autoreg.product.Product;
import com.autoreg.product.ProductImage;
import com.autoreg.product.ProductRepository;
import com.autoreg.product.ProductStatus;
import com.autoreg.tenant.Tenant;
import com.autoreg.tenant.TenantService;

/**
 * 채널 등록 파이프라인: 기존 등록 확인(멱등성) → 이미지 업로드(캐시) → 등록.
 * 채널 API 호출은 트랜잭션 밖에서 하고, 진행 상황은 단계마다 커밋한다.
 */
@Component
public class RegisterJobHandler {

    private final ChannelListingRepository listings;
    private final ChannelAccountRepository accounts;
    private final ChannelImageRepository channelImages;
    private final CategoryMappingRepository mappings;
    private final ProductRepository products;
    private final TenantService tenants;
    private final ChannelAccountService accountService;
    private final ImageStorage storage;
    private final JobService jobs;
    private final TransactionTemplate tx;
    private final Map<com.autoreg.channel.Channel, ChannelAdapter> adapters;

    public RegisterJobHandler(ChannelListingRepository listings, ChannelAccountRepository accounts,
            ChannelImageRepository channelImages, CategoryMappingRepository mappings, ProductRepository products,
            TenantService tenants, ChannelAccountService accountService, ImageStorage storage, JobService jobs,
            TransactionTemplate tx, List<ChannelAdapter> adapters) {
        this.listings = listings;
        this.accounts = accounts;
        this.channelImages = channelImages;
        this.mappings = mappings;
        this.products = products;
        this.tenants = tenants;
        this.accountService = accountService;
        this.storage = storage;
        this.jobs = jobs;
        this.tx = tx;
        this.adapters = adapters.stream().collect(Collectors.toMap(ChannelAdapter::channel, Function.identity()));
    }

    public void handle(Job job) {
        Prepared prep = tx.execute(s -> prepare(job));
        if (prep == null) {
            jobs.succeed(job.getId(), "이미 등록 완료된 상품");
            return;
        }
        RegistrationContext ctx = prep.ctx();
        if (prep.verifyOnly() != null) {
            // 이미 등록된 상품. 다시 POST 하지 않고 재조회 검증만 한다
            jobs.step(job.getId(), "VERIFY", "등록된 상품 " + prep.verifyOnly() + " 재조회");
            verifyAndFinish(job, prep, prep.verifyOnly(), prep.lastResponse());
            return;
        }
        try {
            jobs.step(job.getId(), "LOOKUP", "판매자 상품코드로 기존 등록 확인");
            Optional<String> existing = prep.adapter().findExisting(ctx);
            if (existing.isPresent()) {
                complete(prep.listingId(), existing.get(), Map.of("note", "이미 등록된 상품을 찾아 연결함"));
                jobs.succeed(job.getId(), "이미 등록되어 있음: 상품번호 " + existing.get());
                return;
            }

            List<ProductImage> images = ordered(ctx.product());
            jobs.step(job.getId(), "IMAGES", "이미지 " + images.size() + "장 업로드");
            Map<Long, String> cached = channelImages.findByChannelAccountIdAndProductImageIdIn(ctx.account().getId(),
                    images.stream().map(ProductImage::getId).toList()).stream()
                    .collect(Collectors.toMap(ChannelImage::getProductImageId, ChannelImage::getChannelUrl, (a, b) -> b));
            List<UploadedImage> uploaded = new ArrayList<>();
            for (ProductImage img : images) {
                String url = cached.get(img.getId());
                if (url == null) {
                    Path file = storage.resolve(img.getPath());
                    if (!Files.isRegularFile(file)) {
                        throw JobException.invalid("이미지 파일이 없습니다: " + img.getPath());
                    }
                    url = prep.adapter().uploadImage(ctx, img, file);
                    saveImage(img.getId(), ctx.account().getId(), url);
                }
                uploaded.add(new UploadedImage(img, url));
            }

            jobs.step(job.getId(), "REGISTER", "상품 등록 요청");
            ChannelAdapter.Result result = prep.adapter().register(ctx, uploaded);
            // 상품번호부터 저장한다. 이후 어떤 실패가 나도 재시도가 다시 등록하지 않게
            complete(prep.listingId(), result.channelProductNo(), result.rawResponse());
            jobs.step(job.getId(), "VERIFY", "상품번호 " + result.channelProductNo() + " 재조회 검증");
            verifyAndFinish(job, prep, result.channelProductNo(), result.rawResponse());
        } catch (ChannelException e) {
            if (e.rawResponse() != null) {
                tx.executeWithoutResult(s -> listings.findById(prep.listingId()).ifPresent(l -> l.setLastResponse(e.rawResponse())));
            }
            throw e.retryable() ? JobException.retryable(e.getMessage(), e.retryAfter()) : JobException.invalid(e.getMessage());
        }
    }

    private void verifyAndFinish(Job job, Prepared prep, String productNo, Map<String, Object> raw) {
        Optional<String> problem = prep.adapter().verify(prep.ctx(), productNo, raw);
        tx.executeWithoutResult(s -> listings.findById(prep.listingId()).ifPresent(l -> {
            Map<String, Object> next = new java.util.LinkedHashMap<>(l.getLastResponse() == null ? Map.of() : l.getLastResponse());
            next.put("verification", problem.<Map<String, Object>>map(p -> Map.of("ok", false, "message", p)).orElse(Map.of("ok", true)));
            l.setLastResponse(next);
        }));
        if (problem.isPresent()) {
            throw JobException.invalid("등록은 됐지만 재조회 검증 실패 (상품번호 " + productNo + "): " + problem.get()
                    + " — 다시 시도하면 등록 없이 검증만 합니다");
        }
        jobs.succeed(job.getId(), "등록 완료·재조회 확인: 상품번호 " + productNo);
    }

    /** 작업 실패 결과를 등록 행 상태에 반영한다 */
    public void onFailed(Job job, JobStatus status) {
        tx.executeWithoutResult(s -> listings.findById(job.getChannelListingId()).ifPresent(l -> {
            if (l.getStatus() != ListingStatus.COMPLETED) {
                l.setStatus(status == JobStatus.FAILED_INVALID ? ListingStatus.FAILED_INVALID : ListingStatus.FAILED_RETRYABLE);
            }
        }));
    }

    /** verifyOnly: 이미 등록됐지만 검증이 안 끝난 상품번호 (있으면 등록하지 않는다) */
    private record Prepared(Long listingId, ChannelAdapter adapter, RegistrationContext ctx, String verifyOnly,
            Map<String, Object> lastResponse) {}

    private Prepared prepare(Job job) {
        ChannelListing listing = listings.findById(job.getChannelListingId())
                .orElseThrow(() -> JobException.invalid("등록 행이 없습니다"));
        String verifyOnly = null;
        if (listing.getStatus() == ListingStatus.COMPLETED) {
            if (!verificationFailed(listing)) {
                return null;
            }
            verifyOnly = listing.getChannelProductNo();
        }
        Product product = products.findByIdAndTenantId(listing.getProductId(), listing.getTenantId())
                .orElseThrow(() -> JobException.invalid("상품이 없습니다"));
        if (product.getStatus() != ProductStatus.APPROVED) {
            throw JobException.invalid("승인된 상품만 등록합니다 (현재 " + product.getStatus() + ")");
        }
        ChannelAccount account = accounts.findByIdAndTenantId(listing.getChannelAccountId(), listing.getTenantId())
                .orElseThrow(() -> JobException.invalid("채널 계정이 없습니다"));
        if (!account.isActive()) {
            throw JobException.invalid("채널 계정이 비활성 상태입니다");
        }
        ChannelAdapter adapter = adapters.get(account.getChannel());
        if (adapter == null) {
            throw JobException.invalid(account.getChannel() + " 어댑터가 없습니다");
        }
        com.autoreg.channel.CategoryMapping mapping = mappings.findByTenantIdAndChannelAndCategory(listing.getTenantId(),
                account.getChannel(), product.getCategory())
                .orElseThrow(() -> JobException.invalid("카테고리 매핑이 없습니다: " + product.getCategory() + " → "
                        + account.getChannel() + " (판매자 관리 화면에서 추가)"));
        String categoryId = mapping.getChannelCategoryId();
        // 트랜잭션 밖에서 쓰므로 지연 로딩 컬렉션을 미리 읽어 둔다
        product.getOptions().size();
        product.getMeasurements().size();
        product.getImages().size();
        Tenant tenant = tenants.get(listing.getTenantId());
        if (verifyOnly == null) {
            listing.setStatus(ListingStatus.REGISTERING);
        }
        return new Prepared(listing.getId(), adapter, new RegistrationContext(tenant, product, account,
                accountService.credentials(account), categoryId, mapping.getReference(), listing.getIdempotencyKey()),
                verifyOnly, listing.getLastResponse());
    }

    static boolean verificationFailed(ChannelListing l) {
        return l.getLastResponse() != null && l.getLastResponse().get("verification") instanceof Map<?, ?> v
                && Boolean.FALSE.equals(v.get("ok"));
    }

    private void complete(Long listingId, String productNo, Map<String, Object> raw) {
        tx.executeWithoutResult(s -> {
            ChannelListing l = listings.findById(listingId).orElseThrow();
            l.setStatus(ListingStatus.COMPLETED);
            l.setChannelProductNo(productNo);
            l.setLastResponse(raw);
            l.setRegisteredAt(OffsetDateTime.now());
        });
    }

    private void saveImage(Long productImageId, Long accountId, String url) {
        tx.executeWithoutResult(s -> {
            ChannelImage ci = new ChannelImage();
            ci.setProductImageId(productImageId);
            ci.setChannelAccountId(accountId);
            ci.setChannelUrl(url);
            channelImages.save(ci);
        });
    }

    static List<ProductImage> ordered(Product p) {
        return p.getImages().stream()
                .sorted(Comparator.comparing((ProductImage i) -> i.getSlot().ordinal()).thenComparingInt(ProductImage::getSeq))
                .toList();
    }
}
