package com.pulseops.monitor;

import com.pulseops.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Integration tests for the monitor CRUD endpoints.
 *
 * Uses a real Postgres DB via Testcontainers and the full Spring Security filter chain.
 * Tests register users, obtain JWTs, and hit the monitor endpoints exactly as a real client would.
 *
 * Coverage:
 * - POST /api/v1/monitors          → 201 on valid URL, 400 on bad fields, 422 on SSRF
 * - GET  /api/v1/monitors          → paginated list (only caller's monitors)
 * - GET  /api/v1/monitors/{id}     → 200 for owner, 404 for another user's monitor
 * - PUT  /api/v1/monitors/{id}     → 200 update, 422 on SSRF URL change
 * - DELETE /api/v1/monitors/{id}   → 204 hard delete, subsequent GET → 404
 * - POST /api/v1/monitors/{id}/pause  → enabled = false
 * - POST /api/v1/monitors/{id}/resume → enabled = true, nextCheckAt reset
 * - Unauthenticated requests        → 401
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@ActiveProfiles("test")
class MonitorIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",      postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired WebApplicationContext context;
    @Autowired UserRepository        userRepository;

    MockMvc mockMvc;

    /** Extract numeric "id" field from a JSON response body without needing ObjectMapper bean. */
    private Long extractId(String json) {
        return Long.parseLong(json.replaceAll(".*\"id\"\\s*:\\s*(\\d+).*", "$1"));
    }

    // ── Reusable request builders ─────────────────────────────────────────────

    /** Register a user and return the JWT token. */
    private String registerAndLogin(String email, String password) throws Exception {
        String registerBody = """
                {"email":"%s","password":"%s","name":"Test User"}
                """.formatted(email, password).trim();

        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody))
                .andExpect(status().isCreated());

        String loginBody = """
                {"email":"%s","password":"%s"}
                """.formatted(email, password).trim();

        return mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString()
                .replaceAll(".*\"token\":\"([^\"]+)\".*", "$1");
    }

    /** Minimal valid CreateMonitorRequest body. */
    private String monitorBody(String name, String url) {
        return """
                {"name":"%s","url":"%s"}
                """.formatted(name, url).trim();
    }

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders
                .webAppContextSetup(context)
                .apply(SecurityMockMvcConfigurers.springSecurity())
                .build();
        userRepository.deleteAll();
    }

    // ── 401 Unauthenticated ───────────────────────────────────────────────────

    @Test
    void createMonitor_withoutJwt_returns401() throws Exception {
        mockMvc.perform(post("/api/v1/monitors")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(monitorBody("My monitor", "https://example.com")))
                .andExpect(status().isUnauthorized());
    }

    // ── 201 Create happy path ─────────────────────────────────────────────────

    @Test
    void createMonitor_validPublicUrl_returns201WithBody() throws Exception {
        String token = registerAndLogin("alice@example.com", "Password1!");

        mockMvc.perform(post("/api/v1/monitors")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(monitorBody("My API", "https://example.com/health")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNumber())
                .andExpect(jsonPath("$.name").value("My API"))
                .andExpect(jsonPath("$.url").value("https://example.com/health"))
                .andExpect(jsonPath("$.enabled").value(true))
                // Scheduler internals must NOT be in the response
                .andExpect(jsonPath("$.checkStatus").doesNotExist())
                .andExpect(jsonPath("$.claimToken").doesNotExist())
                .andExpect(jsonPath("$.claimedUntil").doesNotExist());
    }

    // ── 400 Bean Validation ───────────────────────────────────────────────────

    @Test
    void createMonitor_missingName_returns400WithFieldErrors() throws Exception {
        String token = registerAndLogin("bob@example.com", "Password1!");

        String body = """
                {"url":"https://example.com"}
                """.trim();

        mockMvc.perform(post("/api/v1/monitors")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors").isArray())
                .andExpect(jsonPath("$.fieldErrors[*].field", hasItem("name")));
    }

    @Test
    void createMonitor_intervalOutOfRange_returns400() throws Exception {
        String token = registerAndLogin("carol@example.com", "Password1!");

        String body = """
                {"name":"Bad Monitor","url":"https://example.com","intervalMinutes":999}
                """.trim();

        mockMvc.perform(post("/api/v1/monitors")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[*].field", hasItem("intervalMinutes")));
    }

    // ── 422 SSRF ──────────────────────────────────────────────────────────────

    @Test
    void createMonitor_loopbackUrl_returns422() throws Exception {
        String token = registerAndLogin("dave@example.com", "Password1!");

        mockMvc.perform(post("/api/v1/monitors")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(monitorBody("Internal", "http://127.0.0.1:8080/metrics")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("Unprocessable Content"));
    }

    @Test
    void createMonitor_privateIpUrl_returns422() throws Exception {
        String token = registerAndLogin("eve@example.com", "Password1!");

        mockMvc.perform(post("/api/v1/monitors")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(monitorBody("Internal", "http://192.168.1.1/admin")))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void createMonitor_awsMetadataUrl_returns422() throws Exception {
        String token = registerAndLogin("frank@example.com", "Password1!");

        mockMvc.perform(post("/api/v1/monitors")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(monitorBody("AWS Meta", "http://169.254.169.254/latest/meta-data/")))
                .andExpect(status().isUnprocessableEntity());
    }

    // ── GET list ──────────────────────────────────────────────────────────────

    @Test
    void listMonitors_returnOnlyOwnMonitors() throws Exception {
        String aliceToken = registerAndLogin("alice2@example.com", "Password1!");
        String bobToken   = registerAndLogin("bob2@example.com",   "Password1!");

        // Alice creates 2 monitors
        mockMvc.perform(post("/api/v1/monitors")
                        .header("Authorization", "Bearer " + aliceToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(monitorBody("Alice Monitor 1", "https://example.com")))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/v1/monitors")
                        .header("Authorization", "Bearer " + aliceToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(monitorBody("Alice Monitor 2", "https://example.org")))
                .andExpect(status().isCreated());

        // Bob creates 1 monitor
        mockMvc.perform(post("/api/v1/monitors")
                        .header("Authorization", "Bearer " + bobToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(monitorBody("Bob Monitor", "https://example.net")))
                .andExpect(status().isCreated());

        // Alice sees exactly 2
        mockMvc.perform(get("/api/v1/monitors")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.content[*].name",
                        containsInAnyOrder("Alice Monitor 1", "Alice Monitor 2")));

        // Bob sees exactly 1
        mockMvc.perform(get("/api/v1/monitors")
                        .header("Authorization", "Bearer " + bobToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1));
    }

    // ── GET by ID — ownership 404-not-403 ─────────────────────────────────────

    @Test
    void getMonitorById_anotherUsersMonitor_returns404NotForbidden() throws Exception {
        String aliceToken = registerAndLogin("alice3@example.com", "Password1!");
        String bobToken   = registerAndLogin("bob3@example.com",   "Password1!");

        // Alice creates a monitor and captures its ID
        String responseBody = mockMvc.perform(post("/api/v1/monitors")
                        .header("Authorization", "Bearer " + aliceToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(monitorBody("Alice's Secret", "https://example.com")))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        Long aliceMonitorId = extractId(responseBody);

        // Bob tries to access Alice's monitor — must get 404, not 403
        mockMvc.perform(get("/api/v1/monitors/" + aliceMonitorId)
                        .header("Authorization", "Bearer " + bobToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404));
    }

    // ── PUT update ────────────────────────────────────────────────────────────

    @Test
    void updateMonitor_validChange_returns200() throws Exception {
        String token = registerAndLogin("alice4@example.com", "Password1!");

        String createResponse = mockMvc.perform(post("/api/v1/monitors")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(monitorBody("Original Name", "https://example.com")))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        Long id = extractId(createResponse);

        mockMvc.perform(put("/api/v1/monitors/" + id)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(monitorBody("Updated Name", "https://example.org")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Updated Name"))
                .andExpect(jsonPath("$.url").value("https://example.org"));
    }

    @Test
    void updateMonitor_ssrfUrl_returns422() throws Exception {
        String token = registerAndLogin("alice5@example.com", "Password1!");

        String createResponse = mockMvc.perform(post("/api/v1/monitors")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(monitorBody("Safe Monitor", "https://example.com")))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        Long id = extractId(createResponse);

        // Try to update with internal URL
        mockMvc.perform(put("/api/v1/monitors/" + id)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(monitorBody("Pwned", "http://10.0.0.1/secrets")))
                .andExpect(status().isUnprocessableEntity());
    }

    // ── DELETE ────────────────────────────────────────────────────────────────

    @Test
    void deleteMonitor_returns204_thenGetReturns404() throws Exception {
        String token = registerAndLogin("alice6@example.com", "Password1!");

        String createResponse = mockMvc.perform(post("/api/v1/monitors")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(monitorBody("To Delete", "https://example.com")))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        Long id = extractId(createResponse);

        // Delete returns 204 No Content
        mockMvc.perform(delete("/api/v1/monitors/" + id)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNoContent());

        // Subsequent GET returns 404
        mockMvc.perform(get("/api/v1/monitors/" + id)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound());
    }

    // ── Pause / Resume ────────────────────────────────────────────────────────

    @Test
    void pauseMonitor_setsEnabledFalse() throws Exception {
        String token = registerAndLogin("alice7@example.com", "Password1!");

        String createResponse = mockMvc.perform(post("/api/v1/monitors")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(monitorBody("Active Monitor", "https://example.com")))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        Long id = extractId(createResponse);

        mockMvc.perform(post("/api/v1/monitors/" + id + "/pause")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(false));
    }

    @Test
    void resumeMonitor_setsEnabledTrue() throws Exception {
        String token = registerAndLogin("alice8@example.com", "Password1!");

        String createResponse = mockMvc.perform(post("/api/v1/monitors")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(monitorBody("Paused Monitor", "https://example.com")))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        Long id = extractId(createResponse);

        // Pause it first
        mockMvc.perform(post("/api/v1/monitors/" + id + "/pause")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(false));

        // Resume restores enabled = true
        mockMvc.perform(post("/api/v1/monitors/" + id + "/resume")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(true));
    }

    // ── Quota enforcement (max 20 monitors) ───────────────────────────────────

    @Test
    void createMonitor_exceedingQuota20_returns400() throws Exception {
        String token = registerAndLogin("quota@example.com", "Password1!");

        for (int i = 1; i <= 20; i++) {
            mockMvc.perform(post("/api/v1/monitors")
                            .header("Authorization", "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(monitorBody("Monitor " + i, "https://example.com")))
                    .andExpect(status().isCreated());
        }

        // 21st monitor must be rejected with 400
        mockMvc.perform(post("/api/v1/monitors")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(monitorBody("Monitor 21", "https://example.com")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("Maximum 20 monitors")));
    }
}
