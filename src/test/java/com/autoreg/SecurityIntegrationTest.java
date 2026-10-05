package com.autoreg;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@SpringBootTest(properties = {
        "autoreg.master-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
        "autoreg.admin.user=admin", "autoreg.admin.password=test-password-123",
        "autoreg.image-root=/tmp/autoreg-sec",
        "management.health.redis.enabled=false"})
@Testcontainers
class SecurityIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16");

    @Autowired
    WebApplicationContext context;

    MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(SecurityMockMvcConfigurers.springSecurity()).build();
    }

    @Test
    void everythingButLoginAndHealthNeedsSession() throws Exception {
        mvc.perform(get("/api/tenants")).andExpect(status().isUnauthorized());
        mvc.perform(get("/files/shop-a/x.jpg")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/excel/template")).andExpect(status().isUnauthorized());
        mvc.perform(get("/actuator/health")).andExpect(status().isOk());
        // XSRF-TOKEN 쿠키 발급은 MockMvc 가 CSRF 저장소를 테스트용으로 바꿔 끼워 여기서 볼 수 없다 (배포 후 curl 로 확인)
        mvc.perform(get("/api/auth/me")).andExpect(jsonPath("$.authenticated").value(false));
    }

    @Test
    void loginCreatesSessionAndMutationsNeedCsrf() throws Exception {
        mvc.perform(post("/api/auth/login").with(SecurityMockMvcRequestPostProcessors.csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"username\":\"admin\",\"password\":\"wrong-password\"}"))
                .andExpect(status().isUnauthorized());

        MockHttpSession session = (MockHttpSession) mvc.perform(post("/api/auth/login")
                        .with(SecurityMockMvcRequestPostProcessors.csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"admin\",\"password\":\"test-password-123\"}"))
                .andExpect(status().isOk())
                .andReturn().getRequest().getSession(false);

        mvc.perform(get("/api/tenants").session(session)).andExpect(status().isOk());
        mvc.perform(get("/api/auth/me").session(session)).andExpect(jsonPath("$.username").value("admin"));
        mvc.perform(post("/api/tenants").session(session).contentType(MediaType.APPLICATION_JSON)
                .content("{\"code\":\"shop-a\",\"name\":\"A\"}")).andExpect(status().isForbidden());
        mvc.perform(post("/api/tenants").session(session).with(SecurityMockMvcRequestPostProcessors.csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"code\":\"shop-a\",\"name\":\"A\"}"))
                .andExpect(status().isCreated());

        // 같은 세션 쿠키로 확장 토큰 요청이 와도 관리자 세션이 바뀌지 않는다
        String issued = mvc.perform(post("/api/intake-tokens").session(session).with(SecurityMockMvcRequestPostProcessors.csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{}")).andReturn().getResponse().getContentAsString();
        String token = com.jayway.jsonpath.JsonPath.read(issued, "$.token");
        mvc.perform(get("/api/intake/tenants").session(session).header("Authorization", "Bearer " + token)).andExpect(status().isOk());
        mvc.perform(get("/api/tenants").session(session)).andExpect(status().isOk());

        mvc.perform(post("/api/auth/logout").session(session).with(SecurityMockMvcRequestPostProcessors.csrf()))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/tenants").session(session)).andExpect(status().isUnauthorized());
    }
}
