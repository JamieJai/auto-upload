package com.autoreg;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import com.autoreg.channel.Channel;
import com.autoreg.channel.adapter.ChannelAdapter;
import com.autoreg.channel.adapter.ChannelException;
import com.autoreg.channel.adapter.RegistrationContext;
import com.autoreg.llm.LlmClient;
import com.autoreg.product.ProductImage;
import com.autoreg.workflow.JobWorker;
import com.jayway.jsonpath.JsonPath;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

@SpringBootTest(properties = {
        "autoreg.master-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
        "autoreg.admin.user=admin", "autoreg.admin.password=test-password-123",
        "management.health.redis.enabled=false",
        "autoreg.worker.poll-ms=86400000"})
@ActiveProfiles("worker")
@Testcontainers
class WorkflowIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16");

    static Path imageRoot;
    static com.autoreg.channel.naver.FakeNaver naver;

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) throws Exception {
        imageRoot = Files.createTempDirectory("autoreg-wf");
        r.add("autoreg.image-root", () -> imageRoot.toString());
        naver = new com.autoreg.channel.naver.FakeNaver();
        r.add("autoreg.naver.base-url", naver::baseUrl);
    }

    /** 요청된 필드만 채워 돌려주는 LLM. mode 로 실패를 흉내 낸다 */
    static class FakeLlm implements LlmClient {
        final JsonMapper json = JsonMapper.builder().build();
        volatile String mode = "ok";
        final AtomicInteger calls = new AtomicInteger();
        volatile String lastPrompt;

        @Override
        public JsonNode generate(String system, String prompt, String schema) {
            calls.incrementAndGet();
            lastPrompt = prompt;
            ObjectNode n = json.createObjectNode();
            if (schema.contains("wholesalePrice")) {
                n.put("wholesalePrice", 18000).put("material", "면 100%").putNull("originCountry").put("washCare", "단독 손세탁")
                        .put("category", "원피스").put("nameHint", "베이직 셔츠 원피스");
                n.putArray("colors").add("블랙").add("아이보리");
                n.putArray("sizes");
                var m = n.putArray("measurements").addObject().put("size", "FREE");
                m.putArray("parts").add(json.createObjectNode().put("part", "총장").put("cm", 108))
                        .add(json.createObjectNode().put("part", "소매길이").put("cm", 61));
                return n;
            }
            if (mode.equals("bad")) {
                return n.put("name", "").put("description", "짧음");
            }
            if (schema.contains("\"name\"")) {
                n.put("name", "AI 린넨 원피스");
            }
            if (schema.contains("\"description\"")) {
                n.put("description", "가볍고 시원한 린넨 원피스입니다. ".repeat(5));
            }
            if (schema.contains("\"searchKeywords\"")) {
                n.putArray("searchKeywords").add("린넨원피스").add("여름원피스").add("린넨원피스");
            }
            if (schema.contains("\"optionDisplays\"")) {
                n.putArray("optionDisplays").addObject().put("color", "블랙").put("display", "딥 블랙");
            }
            // 스키마에 없는 금지 항목을 섞어도 반영되면 안 된다
            n.put("material", "실크 100%");
            return n;
        }
    }

    /** CAFE24 자리에 꽂는 가짜 채널 */
    static class FakeChannel implements ChannelAdapter {
        final AtomicInteger uploads = new AtomicInteger();
        final AtomicInteger registers = new AtomicInteger();
        final List<String> failNext = new ArrayList<>();
        volatile String existingFor;

        @Override
        public Channel channel() {
            return Channel.CAFE24;
        }

        @Override
        public Optional<String> findExisting(RegistrationContext ctx) {
            return ctx.product().getCode().equals(existingFor) ? Optional.of("EXIST-1") : Optional.empty();
        }

        @Override
        public String uploadImage(RegistrationContext ctx, ProductImage image, Path file) {
            assertThat(file).exists();
            uploads.incrementAndGet();
            return "https://cdn.example/" + image.getSlot().value() + image.getSeq();
        }

        @Override
        public Result register(RegistrationContext ctx, List<UploadedImage> images) {
            if (!failNext.isEmpty()) {
                String kind = failNext.remove(0);
                throw new ChannelException(kind + " failure token=abc123", kind.equals("retry"), null,
                        java.util.Map.of("code", kind));
            }
            registers.incrementAndGet();
            assertThat(ctx.credentials()).containsEntry("mallId", "shop");
            assertThat(images.get(0).image().getSlot().value()).isEqualTo("main");
            return new Result("C24-" + ctx.product().getCode(), java.util.Map.of("ok", true, "category", ctx.channelCategoryId()));
        }
    }

    @TestConfiguration
    static class Fakes {
        @Bean
        @Primary
        FakeLlm fakeLlm() {
            return new FakeLlm();
        }

        @Bean
        FakeChannel fakeChannel() {
            return new FakeChannel();
        }
    }

    @Autowired WebApplicationContext context;
    @Autowired JdbcTemplate jdbc;
    @Autowired JobWorker worker;
    @Autowired FakeLlm llm;
    @Autowired FakeChannel channel;

    MockMvc mvc;
    long tenant;

    @BeforeEach
    void setUp() throws Exception {
        mvc = MockMvcBuilders.webAppContextSetup(context)
                .apply(org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity())
                .defaultRequest(get("/").with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user("admin").roles("ADMIN"))
                        .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf()))
                .build();
        jdbc.execute("TRUNCATE tenant, channel_account, category_mapping, product, job RESTART IDENTITY CASCADE");
        llm.mode = "ok";
        channel.failNext.clear();
        channel.existingFor = null;
        channel.uploads.set(0);
        channel.registers.set(0);
        naver.readbackMismatch = false;
        synchronized (naver.requests) {
            naver.requests.clear();
        }
        naver.registerBodies.clear();
        naver.uploadedTypes.clear();
        naver.existingCode = null;
        tenant = id(mvc.perform(json(post("/api/tenants"), """
                {"code":"shop-a","name":"샵에이","brandTone":"반말, 이모지 금지",
                 "noticeDefaults":{"manufacturer":"(주)샵에이","origin_country":"대한민국","wash_care":"드라이",
                   "quality_assurance":"관련 법령에 따름","as_manager":"고객센터","as_phone":"02-000-0000"}}""")));
        mvc.perform(json(post("/api/tenants/{t}/channel-accounts", tenant), """
                {"channel":"CAFE24","displayName":"샵에이 카페24","credentials":{"mallId":"shop"}}"""))
                .andExpect(status().isCreated());
        mvc.perform(json(put("/api/tenants/{t}/category-mappings", tenant), """
                {"channel":"CAFE24","category":"원피스","channelCategoryId":"24"}"""));
    }

    @Test
    void submitGenerateApproveRegister() throws Exception {
        long p = completeProduct("SS1");
        mvc.perform(post("/api/tenants/{t}/products/{id}/submit", tenant, p))
                .andExpect(jsonPath("$.status").value("GENERATING"));
        worker.poll();
        assertThat(llm.lastPrompt).contains("반말, 이모지 금지").contains("린넨 100%").contains("총장 110cm");
        mvc.perform(get("/api/tenants/{t}/products/{id}", tenant, p))
                .andExpect(jsonPath("$.status").value("PENDING_APPROVAL"))
                .andExpect(jsonPath("$.name").value("AI 린넨 원피스"))
                .andExpect(jsonPath("$.searchKeywords.length()").value(2))
                .andExpect(jsonPath("$.fieldSources.DESCRIPTION").value("AI"))
                .andExpect(jsonPath("$.notice.material").value("린넨 100%"));

        mvc.perform(get("/api/approvals")).andExpect(jsonPath("$.content[0].tenantCode").value("shop-a"));
        mvc.perform(post("/api/tenants/{t}/products/{id}/approve", tenant, p))
                .andExpect(jsonPath("$.status").value("APPROVED"));
        worker.poll();
        assertThat(channel.uploads.get()).isEqualTo(6);
        assertThat(jdbc.queryForObject("SELECT channel_product_no FROM channel_listing", String.class)).isEqualTo("C24-SS1");

        String jobs = mvc.perform(get("/api/jobs?tenantId={t}", tenant)).andReturn().getResponse().getContentAsString();
        List<Number> regIds = JsonPath.read(jobs, "$.content[?(@.type=='REGISTER')].id");
        mvc.perform(get("/api/jobs/{id}", regIds.get(0)))
                .andExpect(jsonPath("$.job.status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.listing.status").value("COMPLETED"))
                .andExpect(jsonPath("$.logs[*].step", hasItem("IMAGES")));
        mvc.perform(get("/api/dashboard"))
                .andExpect(jsonPath("$.today.received").value(2))
                .andExpect(jsonPath("$.today.completed").value(1))
                .andExpect(jsonPath("$.today.failed").value(0));
    }

    @Test
    void retryableFailureBacksOffAndReusesUploadedImages() throws Exception {
        long p = approved("SS2");
        channel.failNext.add("retry");
        worker.poll();
        assertThat(jdbc.queryForObject("SELECT status FROM job WHERE type='REGISTER'", String.class)).isEqualTo("FAILED_RETRYABLE");
        assertThat(jdbc.queryForObject("SELECT status FROM channel_listing", String.class)).isEqualTo("FAILED_RETRYABLE");
        String err = jdbc.queryForObject("SELECT last_error FROM job WHERE type='REGISTER'", String.class);
        assertThat(err).contains("token=***").doesNotContain("abc123");
        worker.poll(); // 아직 대기 시간 전이라 아무것도 안 한다
        assertThat(channel.registers.get()).isZero();

        jdbc.update("UPDATE job SET next_run_at = now() WHERE type='REGISTER'");
        worker.poll();
        assertThat(channel.registers.get()).isEqualTo(1);
        assertThat(channel.uploads.get()).isEqualTo(6); // 두 번째 시도에서 다시 올리지 않음
        assertThat(jdbc.queryForObject("SELECT attempt FROM job WHERE type='REGISTER'", Integer.class)).isEqualTo(2);
    }

    @Test
    void invalidFailureWaitsForManualRetry() throws Exception {
        approved("SS3");
        channel.failNext.add("invalid");
        worker.poll();
        Long jobId = jdbc.queryForObject("SELECT id FROM job WHERE type='REGISTER'", Long.class);
        mvc.perform(get("/api/jobs/{id}", jobId))
                .andExpect(jsonPath("$.job.status").value("FAILED_INVALID"))
                .andExpect(jsonPath("$.listing.status").value("FAILED_INVALID"))
                .andExpect(jsonPath("$.listing.lastResponse.code").value("invalid"));
        mvc.perform(get("/api/dashboard")).andExpect(jsonPath("$.today.failed").value(1));

        mvc.perform(post("/api/jobs/{id}/retry", jobId)).andExpect(jsonPath("$.job.status").value("QUEUED"));
        worker.poll();
        assertThat(jdbc.queryForObject("SELECT status FROM channel_listing", String.class)).isEqualTo("COMPLETED");
    }

    @Test
    void existingChannelProductIsLinkedNotDuplicated() throws Exception {
        approved("DUP");
        channel.existingFor = "DUP";
        worker.poll();
        assertThat(channel.registers.get()).isZero();
        assertThat(jdbc.queryForObject("SELECT channel_product_no FROM channel_listing", String.class)).isEqualTo("EXIST-1");
    }

    @Test
    void retryExhaustionMovesToInvalid() throws Exception {
        approved("SS4");
        for (int i = 0; i < 4; i++) {
            channel.failNext.add("retry");
            jdbc.update("UPDATE job SET next_run_at = now() WHERE type='REGISTER'");
            worker.poll();
        }
        assertThat(jdbc.queryForObject("SELECT status FROM job WHERE type='REGISTER'", String.class)).isEqualTo("FAILED_INVALID");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM job_log WHERE message LIKE '%소진%'", Integer.class)).isEqualTo(1);
    }

    @Test
    void badLlmOutputEventuallyReturnsProductToUser() throws Exception {
        long p = completeProduct("SS5");
        llm.mode = "bad";
        mvc.perform(post("/api/tenants/{t}/products/{id}/submit", tenant, p));
        for (int i = 0; i < 4; i++) {
            jdbc.update("UPDATE job SET next_run_at = now()");
            worker.poll();
        }
        mvc.perform(get("/api/tenants/{t}/products/{id}", tenant, p))
                .andExpect(jsonPath("$.status").value("NEEDS_INPUT"))
                .andExpect(jsonPath("$.reviewNote").value(containsString("문구 자동 생성 실패")))
                .andExpect(jsonPath("$.name").doesNotExist());
    }

    @Test
    void submitWithMissingInputIsRejectedWithIssues() throws Exception {
        long p = id(mvc.perform(json(post("/api/tenants/{t}/products", tenant), """
                {"code":"EMPTY","category":"원피스"}""")));
        mvc.perform(post("/api/tenants/{t}/products/{id}/submit", tenant, p))
                .andExpect(status().is(422))
                .andExpect(jsonPath("$.issues[*].field", hasItem("salePrice")))
                .andExpect(jsonPath("$.issues[*].field", hasItem("images.detail")));
        mvc.perform(get("/api/tenants/{t}/products/{id}", tenant, p)).andExpect(jsonPath("$.status").value("NEEDS_INPUT"));
    }

    @Test
    void smartStoreDryRunThenRealRegistration() throws Exception {
        // CAFE24 가짜 계정은 끄고 스마트스토어만 쓴다
        Long cafe = jdbc.queryForObject("SELECT id FROM channel_account WHERE channel='CAFE24'", Long.class);
        mvc.perform(json(put("/api/tenants/{t}/channel-accounts/{id}", tenant, cafe), """
                {"channel":"CAFE24","displayName":"off","active":false}"""));
        String acc = mvc.perform(json(post("/api/tenants/{t}/channel-accounts", tenant), """
                {"channel":"SMARTSTORE","displayName":"charming point","credentials":{"clientId":"%s","clientSecret":"%s"}}"""
                .formatted(com.autoreg.channel.naver.FakeNaver.CLIENT_ID, com.autoreg.channel.naver.FakeNaver.CLIENT_SECRET)))
                .andReturn().getResponse().getContentAsString();
        long accId = ((Number) JsonPath.read(acc, "$.id")).longValue();
        mvc.perform(json(put("/api/tenants/{t}/category-mappings", tenant), """
                {"channel":"SMARTSTORE","category":"원피스","channelCategoryId":"50000807"}"""));

        // 템플릿 없이 승인하면 설정 부족으로 '수정 필요'
        approved("NV1");
        worker.poll();
        assertThat(jdbc.queryForObject("SELECT last_error FROM job WHERE type='REGISTER'", String.class)).contains("레퍼런스");

        // 기존 상품에서 배송·원산지를 가져온다. dryRun 은 기본 켜짐
        mvc.perform(json(post("/api/tenants/{t}/channel-accounts/{id}/template", tenant, accId), """
                {"originProductNo":"123"}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.settings.dryRun").value(true))
                .andExpect(jsonPath("$.settings.displayStatus").value("SUSPENSION"))
                .andExpect(jsonPath("$.settings.originAreaInfo.originAreaCode").value("00"))
                .andExpect(jsonPath("$.settings.naverShoppingSearchInfo.brandName").value("charming_point_"));

        Long jobId = jdbc.queryForObject("SELECT id FROM job WHERE type='REGISTER'", Long.class);
        mvc.perform(post("/api/jobs/{id}/retry", jobId));
        worker.poll();
        mvc.perform(get("/api/jobs/{id}", jobId))
                .andExpect(jsonPath("$.job.status").value("FAILED_INVALID"))
                .andExpect(jsonPath("$.job.lastError").value(containsString("DRY_RUN")))
                .andExpect(jsonPath("$.listing.lastResponse.request.originProduct.leafCategoryId").value("50000807"))
                .andExpect(jsonPath("$.listing.lastResponse.request.originProduct.detailAttribute.sellerCodeInfo.sellerManagementCode").value("NV1"));
        assertThat(naver.registerBodies).isEmpty();
        int uploadsAfterDryRun = (int) naver.requests.stream().filter(r -> r.contains("product-images")).count();
        assertThat(uploadsAfterDryRun).isEqualTo(6);

        // dryRun 끄고 재시도 → 실제 등록. 이미지는 다시 올리지 않는다
        String settings = mvc.perform(get("/api/tenants/{t}/channel-accounts", tenant)).andReturn().getResponse().getContentAsString();
        java.util.List<java.util.Map<String, Object>> all = JsonPath.read(settings, "$[?(@.channel=='SMARTSTORE')].settings");
        java.util.Map<String, Object> st = all.get(0);
        java.util.Map<String, Object> next = new java.util.HashMap<>(st);
        next.put("dryRun", false);
        mvc.perform(json(put("/api/tenants/{t}/channel-accounts/{id}", tenant, accId),
                JsonMapper.builder().build().writeValueAsString(java.util.Map.of("channel", "SMARTSTORE", "displayName", "charming point", "settings", next))))
                .andExpect(jsonPath("$.hasCredentials").value(true));
        mvc.perform(post("/api/jobs/{id}/retry", jobId));
        worker.poll();
        assertThat(jdbc.queryForObject("SELECT channel_product_no FROM channel_listing WHERE status='COMPLETED'", String.class))
                .isEqualTo("13700000001");
        assertThat(naver.registerBodies).hasSize(1);
        assertThat(naver.requests.stream().filter(r -> r.contains("product-images")).count()).isEqualTo(uploadsAfterDryRun);
    }

    @Test
    void categoryReferenceAndReadbackVerification() throws Exception {
        Long cafe = jdbc.queryForObject("SELECT id FROM channel_account WHERE channel='CAFE24'", Long.class);
        mvc.perform(json(put("/api/tenants/{t}/channel-accounts/{id}", tenant, cafe), """
                {"channel":"CAFE24","displayName":"off","active":false}"""));
        String acc = mvc.perform(json(post("/api/tenants/{t}/channel-accounts", tenant), """
                {"channel":"SMARTSTORE","displayName":"cp","settings":{"dryRun":false},"credentials":{"clientId":"%s","clientSecret":"%s"}}"""
                .formatted(com.autoreg.channel.naver.FakeNaver.CLIENT_ID, com.autoreg.channel.naver.FakeNaver.CLIENT_SECRET)))
                .andReturn().getResponse().getContentAsString();
        String map = mvc.perform(json(put("/api/tenants/{t}/category-mappings", tenant), """
                {"channel":"SMARTSTORE","category":"원피스","channelCategoryId":"50000807"}""")).andReturn().getResponse().getContentAsString();
        long mapId = ((Number) JsonPath.read(map, "$.id")).longValue();

        // 테스트 상품은 레퍼런스로 거부, 일반 상품은 허용
        mvc.perform(json(post("/api/tenants/{t}/category-mappings/{id}/reference", tenant, mapId), """
                {"originProductNo":"124"}""")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("테스트")));
        mvc.perform(json(post("/api/tenants/{t}/category-mappings/{id}/reference", tenant, mapId), """
                {"originProductNo":"123"}""")).andExpect(status().isOk())
                .andExpect(jsonPath("$.referenceName").value("린넨 셔츠 롱원피스"))
                .andExpect(jsonPath("$.reference.detailAttribute.productAttributes").isArray());

        // 재조회 결과가 다르면: 상품번호는 저장, 작업은 '수정 필요', 재시도는 등록 없이 검증만
        naver.readbackMismatch = true;
        approved("RB1");
        worker.poll();
        Long jobId = jdbc.queryForObject("SELECT id FROM job WHERE type='REGISTER'", Long.class);
        mvc.perform(get("/api/jobs/{id}", jobId))
                .andExpect(jsonPath("$.job.status").value("FAILED_INVALID"))
                .andExpect(jsonPath("$.job.lastError").value(containsString("재조회 검증 실패")))
                .andExpect(jsonPath("$.listing.channelProductNo").value("13700000001"))
                .andExpect(jsonPath("$.listing.lastResponse.verification.ok").value(false));
        String sent = naver.registerBodies.get(naver.registerBodies.size() - 1);
        assertThat(sent).contains("\"productAttributes\"").doesNotContain("bbsSeq").doesNotContain("customerBenefit")
                .doesNotContain("옛태그").doesNotContain("59836117729");
        int registers = naver.registerBodies.size();

        naver.readbackMismatch = false;
        mvc.perform(post("/api/jobs/{id}/retry", jobId));
        worker.poll();
        mvc.perform(get("/api/jobs/{id}", jobId))
                .andExpect(jsonPath("$.job.status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.listing.lastResponse.verification.ok").value(true));
        assertThat(naver.registerBodies).hasSize(registers);

        // 카테고리 ID 를 바꾸면 레퍼런스가 지워진다
        mvc.perform(json(put("/api/tenants/{t}/category-mappings", tenant), """
                {"channel":"SMARTSTORE","category":"원피스","channelCategoryId":"50000999"}"""))
                .andExpect(jsonPath("$.reference").doesNotExist());
    }

    @Test
    void generateSingleFieldAndApproveGuards() throws Exception {
        long p = completeProduct("SS7");
        mvc.perform(json(post("/api/tenants/{t}/products/{id}/generate", tenant, p), """
                {"field":"OPTION_DISPLAY"}"""))
                .andExpect(jsonPath("$.options[0].colorDisplay").value("딥 블랙"))
                .andExpect(jsonPath("$.options[2].colorDisplay").doesNotExist())
                .andExpect(jsonPath("$.fieldSources.OPTION_DISPLAY").value("AI"))
                .andExpect(jsonPath("$.notice.material").value("린넨 100%"));
        mvc.perform(post("/api/tenants/{t}/products/{id}/approve", tenant, p)).andExpect(status().isConflict());

        // 문구를 사람이 다 채우면 생성 단계 없이 승인 대기로 간다
        mvc.perform(json(put("/api/tenants/{t}/products/{id}", tenant, p), """
                {"code":"SS7","category":"원피스","salePrice":39000,"material":"린넨 100%","name":"직접 쓴 이름",
                 "description":"직접 쓴 설명","searchKeywords":["린넨"],"manufacturer":"(주)샵에이","originCountry":"대한민국",
                 "washCare":"드라이","qualityAssurance":"관련 법령에 따름","asManager":"고객센터","asPhone":"02-000-0000"}"""))
                .andExpect(jsonPath("$.fieldSources.NAME").value("MANUAL"));
        int before = llm.calls.get();
        mvc.perform(post("/api/tenants/{t}/products/{id}/submit", tenant, p))
                .andExpect(jsonPath("$.status").value("PENDING_APPROVAL"));
        assertThat(llm.calls.get()).isEqualTo(before);
        mvc.perform(json(post("/api/tenants/{t}/products/{id}/reject", tenant, p), """
                {"note":"상품명 수정 필요"}""")).andExpect(jsonPath("$.status").value("NEEDS_INPUT"))
                .andExpect(jsonPath("$.reviewNote").value("상품명 수정 필요"));
    }

    @Test
    void browserExtensionIntakeCreatesDraftFromWholesalePage() throws Exception {
        mvc.perform(json(put("/api/tenants/{t}", tenant), """
                {"code":"shop-a","name":"샵에이","productCodePrefix":"SA","priceRule":{"multiplier":2,"roundUnit":1000,"subtract":100,"defaultStock":7},
                 "noticeDefaults":{"manufacturer":"(주)샵에이","wash_care":"기본 세탁"}}""")).andExpect(status().isOk());
        String issued = mvc.perform(json(post("/api/intake-tokens"), "{\"name\":\"크롬\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String token = JsonPath.read(issued, "$.token");
        assertThat(token).startsWith("ar_");

        MockMvc ext = MockMvcBuilders.webAppContextSetup(context)
                .apply(org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity()).build();
        ext.perform(get("/api/intake/tenants")).andExpect(status().isUnauthorized());
        ext.perform(get("/api/intake/tenants").header("Authorization", "Bearer ar_wrong")).andExpect(status().isUnauthorized());
        ext.perform(get("/api/intake/tenants").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].code").value("shop-a"));
        // 토큰으로 다른 API 는 못 쓴다
        ext.perform(get("/api/tenants").header("Authorization", "Bearer " + token)).andExpect(status().isUnauthorized());

        var meta = new MockMultipartFile("meta", "", "application/json", ("""
                {"tenantId":%d,"url":"https://sinsangmarket.kr/goods/1","title":"베이직 셔츠 원피스",
                 "text":"베이직 셔츠 원피스 도매가 18,000원 컬러 블랙 아이보리 FREE 소재 면 100%% 총장 108 단독 손세탁",
                 "images":[{"url":"https://img.example/1.jpg","slot":"main"},{"url":"https://img.example/2.jpg","slot":"detail"}]}"""
                .formatted(tenant)).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        String res = ext.perform(multipart("/api/intake/products").file(meta).file(png("a.png")).file(png("b.png"))
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.imageCount").value(2))
                .andReturn().getResponse().getContentAsString();
        long id = ((Number) JsonPath.read(res, "$.productId")).longValue();
        String code = JsonPath.read(res, "$.code");
        assertThat(code).matches("SA\\d{4}001");

        worker.poll();
        mvc.perform(get("/api/tenants/{t}/products/{id}", tenant, id))
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andExpect(jsonPath("$.category").value("원피스"))
                .andExpect(jsonPath("$.salePrice").value(35900))
                .andExpect(jsonPath("$.notice.material").value("면 100%"))
                .andExpect(jsonPath("$.notice.manufacturer").value("(주)샵에이"))
                .andExpect(jsonPath("$.notice.wash_care").value("단독 손세탁"))
                .andExpect(jsonPath("$.options.length()").value(2))
                .andExpect(jsonPath("$.options[0].size").value("FREE"))
                .andExpect(jsonPath("$.options[0].stock").value(7))
                .andExpect(jsonPath("$.measurements[0].measures['총장']").value(108))
                .andExpect(jsonPath("$.measurements[0].measures['소매길이']").doesNotExist())
                .andExpect(jsonPath("$.images.length()").value(2))
                .andExpect(jsonPath("$.images[0].sourceType").value("WEB"))
                .andExpect(jsonPath("$.reviewNote").value(containsString("소매길이 61")));
        mvc.perform(get("/api/tenants/{t}/products/{id}/source", tenant, id))
                .andExpect(jsonPath("$.sourceUrl").value("https://sinsangmarket.kr/goods/1"))
                .andExpect(jsonPath("$.extracted.wholesalePrice").value(18000));

        // 폐기한 토큰은 더 못 쓴다
        Long tokenId = ((Number) JsonPath.read(issued, "$.id")).longValue();
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete("/api/intake-tokens/{id}", tokenId))
                .andExpect(status().isNoContent());
        ext.perform(get("/api/intake/tenants").header("Authorization", "Bearer " + token)).andExpect(status().isUnauthorized());
    }

    // ---- helpers ----

    private long approved(String code) throws Exception {
        long p = completeProduct(code);
        mvc.perform(json(put("/api/tenants/{t}/products/{id}", tenant, p), """
                {"code":"%s","category":"원피스","salePrice":39000,"material":"린넨 100%%","name":"이름","description":"설명",
                 "searchKeywords":["린넨"],"manufacturer":"(주)샵에이","originCountry":"대한민국","washCare":"드라이",
                 "qualityAssurance":"관련 법령에 따름","asManager":"고객센터","asPhone":"02-000-0000"}""".formatted(code)));
        mvc.perform(post("/api/tenants/{t}/products/{id}/submit", tenant, p)).andExpect(jsonPath("$.status").value("PENDING_APPROVAL"));
        mvc.perform(post("/api/tenants/{t}/products/{id}/approve", tenant, p)).andExpect(jsonPath("$.status").value("APPROVED"));
        return p;
    }

    private long completeProduct(String code) throws Exception {
        long p = id(mvc.perform(json(post("/api/tenants/{t}/products", tenant), """
                {"code":"%s","category":"원피스","salePrice":39000,"material":"린넨 100%%"}""".formatted(code))));
        mvc.perform(json(post("/api/tenants/{t}/products/{id}/options/combine", tenant, p), """
                {"colors":["블랙","아이보리"],"sizes":["S","M"],"stock":3}"""));
        mvc.perform(json(put("/api/tenants/{t}/products/{id}/measurements", tenant, p), """
                {"measurements":[{"size":"S","measures":{"총장":110}},{"size":"M","measures":{"총장":112}}]}"""));
        var req = multipart("/api/tenants/{t}/images", tenant);
        for (String n : List.of("main", "sub_01", "sub_02", "detail_01", "detail_02", "size")) {
            req.file(png(code + "_" + n + ".png"));
        }
        mvc.perform(req).andExpect(jsonPath("$.matched.length()").value(6));
        return p;
    }

    private static MockMultipartFile png(String name) throws Exception {
        var out = new ByteArrayOutputStream();
        javax.imageio.ImageIO.write(new java.awt.image.BufferedImage(600, 800, java.awt.image.BufferedImage.TYPE_INT_RGB), "png", out);
        return new MockMultipartFile("files", name, "image/png", out.toByteArray());
    }

    private static long id(org.springframework.test.web.servlet.ResultActions r) throws Exception {
        return ((Number) JsonPath.read(r.andReturn().getResponse().getContentAsString(), "$.id")).longValue();
    }

    private static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder b, String body) {
        return b.contentType(MediaType.APPLICATION_JSON).content(body);
    }
}
