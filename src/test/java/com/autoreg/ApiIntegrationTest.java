package com.autoreg;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import com.jayway.jsonpath.JsonPath;

@SpringBootTest(properties = {
        "autoreg.master-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
        "autoreg.admin.user=admin", "autoreg.admin.password=test-password-123",
        "management.health.redis.enabled=false",
        "autoreg.web-fetch.allow-private=true"})
@Testcontainers
class ApiIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16");

    static java.nio.file.Path imageRoot;

    @org.springframework.test.context.DynamicPropertySource
    static void imageRoot(org.springframework.test.context.DynamicPropertyRegistry r) throws Exception {
        imageRoot = java.nio.file.Files.createTempDirectory("autoreg-images");
        r.add("autoreg.image-root", () -> imageRoot.toString());
    }

    @Autowired
    WebApplicationContext context;

    @Autowired
    JdbcTemplate jdbc;

    MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context)
                .apply(org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity())
                .defaultRequest(get("/").with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user("admin"))
                        .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf()))
                .build();
        jdbc.execute("TRUNCATE tenant, channel_account, category_mapping, product RESTART IDENTITY CASCADE");
    }

    @Test
    void productFlowWithNoticeDefaultsAndValidation() throws Exception {
        long tenant = createTenant("shop-a");

        String created = mvc.perform(json(post("/api/tenants/{t}/products", tenant), """
                {"code":"SS2609001","category":"원피스","salePrice":39000,"material":"린넨 100%",
                 "name":"린넨 원피스","searchKeywords":["린넨","원피스","린넨"]}"""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("DRAFT"))
                // 판매자 기본값이 비어 있는 고시정보에 채워진다
                .andExpect(jsonPath("$.notice.manufacturer").value("(주)샵에이"))
                .andExpect(jsonPath("$.notice.material").value("린넨 100%"))
                .andExpect(jsonPath("$.searchKeywords", hasSize(2)))
                .andExpect(jsonPath("$.fieldSources.NAME").value("MANUAL"))
                .andReturn().getResponse().getContentAsString();
        long id = ((Number) JsonPath.read(created, "$.id")).longValue();

        mvc.perform(json(post("/api/tenants/{t}/products/{id}/options/combine", tenant, id),
                """
                {"colors":["블랙","아이보리"],"sizes":["S","M"],"stock":5}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.options", hasSize(4)));
        // 같은 조합으로 다시 교체해도 유니크 제약에 걸리지 않는다
        mvc.perform(json(post("/api/tenants/{t}/products/{id}/options/combine", tenant, id),
                """
                {"colors":["블랙","아이보리"],"sizes":["S","M"],"stock":2}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.options[0].stock").value(2));

        mvc.perform(json(put("/api/tenants/{t}/products/{id}/measurements", tenant, id), """
                {"measurements":[{"size":"S","measures":{"총장":110,"가슴단면":48.5}}]}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.measurements[0].measures['가슴단면']").value(48.5));

        mvc.perform(get("/api/tenants/{t}/products/{id}/validation", tenant, id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(false))
                .andExpect(jsonPath("$.issues[*].field", hasItem("measurements.M")))
                .andExpect(jsonPath("$.issues[*].field", hasItem("images.detail")))
                .andExpect(jsonPath("$.issues[*].field", not(hasItem("notice.manufacturer"))));

        mvc.perform(get("/api/tenants/{t}/products/{id}/validation?phase=READY", tenant, id))
                .andExpect(jsonPath("$.issues[*].field", hasItem("description")));

        mvc.perform(get("/api/tenants/{t}/products?status=DRAFT", tenant))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(1)))
                .andExpect(jsonPath("$.page.totalElements").value(1));
    }

    @Test
    void productsAreInvisibleAcrossTenants() throws Exception {
        long a = createTenant("shop-a");
        long b = createTenant("shop-b");
        String created = mvc.perform(json(post("/api/tenants/{t}/products", a), """
                {"code":"SS1"}""")).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        long id = ((Number) JsonPath.read(created, "$.id")).longValue();

        mvc.perform(get("/api/tenants/{t}/products/{id}", b, id)).andExpect(status().isNotFound());
        mvc.perform(json(put("/api/tenants/{t}/products/{id}", b, id), """
                {"code":"SS1","name":"탈취"}""")).andExpect(status().isNotFound());
        mvc.perform(delete("/api/tenants/{t}/products/{id}", b, id)).andExpect(status().isNotFound());
        // 같은 상품코드라도 판매자가 다르면 따로 만들 수 있다
        mvc.perform(json(post("/api/tenants/{t}/products", b), """
                {"code":"SS1"}""")).andExpect(status().isCreated());
        mvc.perform(json(post("/api/tenants/{t}/products", a), """
                {"code":"SS1"}""")).andExpect(status().isConflict());
    }

    @Test
    void credentialsAreEncryptedAndNeverReturned() throws Exception {
        long t = createTenant("shop-a");
        mvc.perform(json(post("/api/tenants/{t}/channel-accounts", t), """
                {"channel":"SMARTSTORE","displayName":"샵에이 스마트스토어",
                 "credentials":{"clientId":"cid","clientSecret":"TOPSECRET"}}"""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.hasCredentials").value(true))
                .andExpect(content().string(not(org.hamcrest.Matchers.containsString("TOPSECRET"))));

        byte[] stored = jdbc.queryForObject("SELECT credentials_enc FROM channel_account", byte[].class);
        assertThat(new String(stored, java.nio.charset.StandardCharsets.ISO_8859_1)).doesNotContain("TOPSECRET");

        mvc.perform(json(post("/api/tenants/{t}/channel-accounts", t), """
                {"channel":"SMARTSTORE","displayName":"중복"}""")).andExpect(status().isConflict());

        mvc.perform(json(put("/api/tenants/{t}/category-mappings", t), """
                {"channel":"SMARTSTORE","category":"원피스","channelCategoryId":"50000807"}"""))
                .andExpect(status().isOk());
        mvc.perform(json(put("/api/tenants/{t}/category-mappings", t), """
                {"channel":"SMARTSTORE","category":"원피스","channelCategoryId":"50000808"}"""))
                .andExpect(status().isOk());
        mvc.perform(get("/api/tenants/{t}/category-mappings", t))
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].channelCategoryId").value("50000808"));
    }

    @Test
    void rejectsBadInput() throws Exception {
        mvc.perform(json(post("/api/tenants"), """
                {"code":"Shop A","name":""}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fields[*].field", containsInAnyOrder("code", "name")));
        mvc.perform(json(post("/api/tenants"), """
                {"code":"shop-x","name":"X","noticeDefaults":{"price":"1000"}}"""))
                .andExpect(status().isBadRequest());
        long t = createTenant("shop-a");
        mvc.perform(json(post("/api/tenants/{t}/products", t), """
                {"code":"SS1","salePrice":-1}""")).andExpect(status().isBadRequest());
    }

    @Test
    void uploadMatchesByFilenameAndParksUnknownFiles() throws Exception {
        long t = createTenant("shop-a");
        long b = createTenant("shop-b");
        String created = mvc.perform(json(post("/api/tenants/{t}/products", t), """
                {"code":"SS2609001"}""")).andReturn().getResponse().getContentAsString();
        long id = ((Number) JsonPath.read(created, "$.id")).longValue();
        // 다른 판매자에 같은 코드가 있어도 섞이지 않는다
        mvc.perform(json(post("/api/tenants/{t}/products", b), """
                {"code":"SS2609001"}""")).andExpect(status().isCreated());

        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .multipart("/api/tenants/{t}/images", t)
                        .file(png("SS2609001_main.png", 1200, 1600))
                        .file(png("SS2609001_detail_02.png", 800, 800))
                        .file(png("SS9999999_main.png", 100, 100))
                        .file(png("random.png", 100, 100))
                        .file(new org.springframework.mock.web.MockMultipartFile("files", "SS2609001_sub.png",
                                "image/png", "not an image".getBytes())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.matched", hasSize(2)))
                .andExpect(jsonPath("$.unmatched", hasSize(3)));

        assertThat(imageRoot.resolve("shop-a/SS2609001/main_01.png")).exists();
        assertThat(imageRoot.resolve("shop-a/_thumbs/SS2609001/main_01.jpg")).exists();
        assertThat(imageRoot.resolve("shop-a/_unmatched/random.png")).exists();
        assertThat(imageRoot.resolve("shop-b/SS2609001")).doesNotExist();
        java.awt.image.BufferedImage thumb = javax.imageio.ImageIO.read(imageRoot.resolve("shop-a/_thumbs/SS2609001/main_01.jpg").toFile());
        assertThat(thumb.getHeight()).isEqualTo(400);

        mvc.perform(get("/api/tenants/{t}/products/{id}", t, id))
                .andExpect(jsonPath("$.images", hasSize(2)))
                .andExpect(jsonPath("$.images[?(@.slot=='main')].width").value(1200));

        mvc.perform(get("/api/tenants/{t}/images/unmatched", t))
                .andExpect(jsonPath("$[*].filename", containsInAnyOrder("SS9999999_main.png", "random.png")));

        mvc.perform(json(post("/api/tenants/{t}/images/unmatched/assign", t), """
                {"filename":"random.png","productId":%d,"slot":"size","seq":1}""".formatted(id)))
                .andExpect(status().isOk());
        assertThat(imageRoot.resolve("shop-a/_unmatched/random.png")).doesNotExist();
        assertThat(imageRoot.resolve("shop-a/SS2609001/size_01.png")).exists();

        // 경로 탈출 시도는 파일명만 남기고 처리한다
        mvc.perform(delete("/api/tenants/{t}/images/unmatched", t).param("filename", "../../shop-b/x.png"))
                .andExpect(status().isNotFound());
        mvc.perform(get("/files/shop-a/_thumbs/SS2609001/main_01.jpg")).andExpect(status().isOk());

        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .multipart("/api/tenants/{t}/products/{id}/images", t, id).file(png("아무이름.png", 300, 300))
                        .file(png("other.png", 300, 300)).param("slot", "detail"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].seq", containsInAnyOrder(3, 4)));
    }

    @Test
    void excelPreviewThenImport() throws Exception {
        long t = createTenant("shop-a");
        mvc.perform(json(post("/api/tenants/{t}/products", t), """
                {"code":"EXISTS"}""")).andExpect(status().isCreated());
        byte[] file = com.autoreg.excel.ExcelTestFiles.filled(new Object[][] {
                com.autoreg.excel.ExcelTestFiles.product("SS1", 39000, "블랙,아이보리", "S,M", 3, "린넨 100%"),
                com.autoreg.excel.ExcelTestFiles.product("EXISTS", 1000, "블랙", "S", 1, "면"),
                com.autoreg.excel.ExcelTestFiles.product("SS2", "x", "블랙", "S", 1, "면")},
                new Object[][] {{"SS1", "S", 100}, {"SS1", "M", 102}});
        var part = new org.springframework.mock.web.MockMultipartFile("file", "a.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", file);

        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .multipart("/api/tenants/{t}/excel/preview", t).file(part))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(3))
                .andExpect(jsonPath("$.importable").value(1))
                .andExpect(jsonPath("$.rows[0].warnings[*].field", not(hasItem("notice.manufacturer"))))
                .andExpect(jsonPath("$.rows[0].warnings[*].field", hasItem("notice.wash_care")));
        // 미리보기는 아무것도 만들지 않는다
        mvc.perform(get("/api/tenants/{t}/products", t)).andExpect(jsonPath("$.page.totalElements").value(1));

        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .multipart("/api/tenants/{t}/excel/import", t).file(part))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.created").value(1))
                .andExpect(jsonPath("$.skipped").value(2));
        String list = mvc.perform(get("/api/tenants/{t}/products?status=DRAFT&sort=code", t))
                .andReturn().getResponse().getContentAsString();
        java.util.List<Number> ids = JsonPath.read(list, "$.content[?(@.code=='SS1')].id");
        long id = ids.get(0).longValue();
        mvc.perform(get("/api/tenants/{t}/products/{id}", t, id))
                .andExpect(jsonPath("$.options", hasSize(4)))
                .andExpect(jsonPath("$.measurements", hasSize(2)))
                .andExpect(jsonPath("$.notice.manufacturer").value("(주)샵에이"));

        mvc.perform(get("/api/excel/template")).andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header()
                        .string("Content-Disposition", org.hamcrest.Matchers.containsString("attachment")));
    }

    @Test
    void webImagesShowCandidatesThenImportOnlyPicked() throws Exception {
        var server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        byte[] big = png("b.png", 800, 1000).getBytes();
        byte[] big2 = png("c.png", 900, 600).getBytes();
        byte[] small = png("s.png", 100, 100).getBytes();
        server.createContext("/", ex -> {
            String path = ex.getRequestURI().getPath();
            byte[] body = switch (path) {
                case "/p/1" -> """
                        <html><body><img src="/big.png"><img src="/big.png?dup=1"><img src="/small.png">
                        <img data-src="/big2.png" src="/x.gif"></body></html>""".getBytes(java.nio.charset.StandardCharsets.UTF_8);
                case "/big.png" -> big;
                case "/big2.png" -> big2;
                case "/small.png" -> small;
                default -> new byte[0];
            };
            ex.getResponseHeaders().add("Content-Type", path.startsWith("/p/") ? "text/html; charset=utf-8" : "image/png");
            ex.sendResponseHeaders(body.length == 0 ? 404 : 200, body.length == 0 ? -1 : body.length);
            if (body.length > 0) {
                ex.getResponseBody().write(body);
            }
            ex.close();
        });
        server.start();
        try {
            String base = "http://localhost:" + server.getAddress().getPort();
            long t = createTenant("shop-a");
            String created = mvc.perform(json(post("/api/tenants/{t}/products", t), """
                    {"code":"WEB1"}""")).andReturn().getResponse().getContentAsString();
            long id = ((Number) JsonPath.read(created, "$.id")).longValue();

            // 허용 도메인이 아니면 거부
            mvc.perform(json(post("/api/tenants/{t}/web-images/candidates", t), "{\"url\":\"" + base + "/p/1\"}"))
                    .andExpect(status().isBadRequest());
            mvc.perform(json(put("/api/tenants/{t}", t), """
                    {"code":"shop-a","name":"shop-a","allowedImageDomains":["localhost"]}""")).andExpect(status().isOk());

            // 작은 이미지와 같은 내용의 중복은 빠진다
            mvc.perform(json(post("/api/tenants/{t}/web-images/candidates", t), "{\"url\":\"" + base + "/p/1\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$", hasSize(2)))
                    .andExpect(jsonPath("$[0].width").value(800));
            // 아무것도 저장되지 않았다
            mvc.perform(get("/api/tenants/{t}/products/{id}", t, id)).andExpect(jsonPath("$.images", hasSize(0)));

            mvc.perform(json(post("/api/tenants/{t}/products/{id}/web-images", t, id), """
                    {"pageUrl":"%s/p/1","picks":[{"url":"%s/big2.png","slot":"detail"}]}""".formatted(base, base)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$[0].slot").value("detail"));
            mvc.perform(get("/api/tenants/{t}/products/{id}", t, id))
                    .andExpect(jsonPath("$.images", hasSize(1)))
                    .andExpect(jsonPath("$.images[0].sourceType").value("WEB"))
                    .andExpect(jsonPath("$.images[0].sourceUrl").value(base + "/big2.png"))
                    .andExpect(jsonPath("$.images[0].width").value(900));
        } finally {
            server.stop(0);
        }
    }

    private static org.springframework.mock.web.MockMultipartFile png(String name, int w, int h) throws Exception {
        var img = new java.awt.image.BufferedImage(w, h, java.awt.image.BufferedImage.TYPE_INT_RGB);
        var out = new java.io.ByteArrayOutputStream();
        javax.imageio.ImageIO.write(img, "png", out);
        return new org.springframework.mock.web.MockMultipartFile("files", name, "image/png", out.toByteArray());
    }

    private long createTenant(String code) throws Exception {
        String body = mvc.perform(json(post("/api/tenants"), """
                {"code":"%s","name":"%s","noticeDefaults":{"manufacturer":"(주)샵에이","origin_country":"대한민국"}}"""
                .formatted(code, code)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(body, "$.id")).longValue();
    }

    private static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder b, String body) {
        return b.contentType(MediaType.APPLICATION_JSON).content(body);
    }
}
