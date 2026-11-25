package com.pulseops.monitor;

import com.pulseops.incident.Incident;
import com.pulseops.incident.IncidentRepository;
import com.pulseops.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
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

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests for Phase 6 endpoints and the 30-day retention purge job:
 * - GET /api/v1/monitors/{id}/results
 * - GET /api/v1/monitors/{id}/uptime
 * - GET /api/v1/monitors/{id}/incidents
 * - GET /api/v1/incidents
 * - CheckResultPurgeJob.purgeExpiredResults()
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@ActiveProfiles("test")
class HistoryAndUptimeIntegrationTest {

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
    @Autowired CheckResultRepository checkResultRepository;
    @Autowired IncidentRepository    incidentRepository;
    @Autowired CheckResultPurgeJob   purgeJob;
    @Autowired JdbcTemplate          jdbcTemplate;

    MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders
                .webAppContextSetup(context)
                .apply(SecurityMockMvcConfigurers.springSecurity())
                .build();
        userRepository.deleteAll();
    }

    private String registerAndLogin(String email) throws Exception {
        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","password":"Password1!"}
                                """.formatted(email).trim()))
                .andExpect(status().isCreated());

        return mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","password":"Password1!"}
                                """.formatted(email).trim()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString()
                .replaceAll(".*\"token\":\"([^\"]+)\".*", "$1");
    }

    private Long createMonitor(String token, String name) throws Exception {
        String body = mockMvc.perform(post("/api/v1/monitors")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"%s","url":"https://example.com"}
                                """.formatted(name).trim()))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return Long.parseLong(body.replaceAll(".*\"id\"\\s*:\\s*(\\d+).*", "$1"));
    }

    private void insertCheckResult(Long monitorId, boolean success, Integer statusCode,
                                   Integer latencyMs, String errorDetail, Instant checkedAt) {
        jdbcTemplate.update(
                """
                INSERT INTO check_results (monitor_id, success, status_code, latency_ms, error_detail, checked_at)
                VALUES (?, ?, ?, ?, ?, ?)
                """,
                monitorId, success, statusCode, latencyMs, errorDetail, Timestamp.from(checkedAt)
        );
    }

    // ── /results ──────────────────────────────────────────────────────────────

    @Test
    void getResults_returnsNewestFirst_andEnforces404Ownership() throws Exception {
        String aliceToken = registerAndLogin("alice-res@example.com");
        String bobToken   = registerAndLogin("bob-res@example.com");
        Long monitorId    = createMonitor(aliceToken, "Alice API");

        Instant now = Instant.now();
        insertCheckResult(monitorId, true, 200, 50, null, now.minus(2, ChronoUnit.MINUTES));
        insertCheckResult(monitorId, false, 503, 120, "HTTP 503", now.minus(1, ChronoUnit.MINUTES));

        // Alice sees 2 results, newest (503) first
        mockMvc.perform(get("/api/v1/monitors/" + monitorId + "/results")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.content[0].success").value(false))
                .andExpect(jsonPath("$.content[0].statusCode").value(503))
                .andExpect(jsonPath("$.content[1].success").value(true))
                .andExpect(jsonPath("$.content[1].statusCode").value(200));

        // Bob gets 404 (not 403)
        mockMvc.perform(get("/api/v1/monitors/" + monitorId + "/results")
                        .header("Authorization", "Bearer " + bobToken))
                .andExpect(status().isNotFound());
    }

    // ── /uptime ───────────────────────────────────────────────────────────────

    @Test
    void getUptime_whenNoChecks_returnsNullPercentages() throws Exception {
        String token   = registerAndLogin("empty-up@example.com");
        Long monitorId = createMonitor(token, "Fresh Monitor");

        mockMvc.perform(get("/api/v1/monitors/" + monitorId + "/uptime")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.monitorId").value(monitorId))
                .andExpect(jsonPath("$.last24Hours.totalChecks").value(0))
                .andExpect(jsonPath("$.last24Hours.uptimePercentage").doesNotExist());
    }

    @Test
    void getUptime_computes24h7d30dWindowsAccurately() throws Exception {
        String aliceToken = registerAndLogin("alice-up@example.com");
        String bobToken   = registerAndLogin("bob-up@example.com");
        Long monitorId    = createMonitor(aliceToken, "Prod API");

        Instant now = Instant.now();
        // Within 24h: 1 UP, 1 DOWN -> 50.0%
        insertCheckResult(monitorId, true, 200, 40, null, now.minus(2, ChronoUnit.HOURS));
        insertCheckResult(monitorId, false, 500, 90, "HTTP 500", now.minus(1, ChronoUnit.HOURS));
        // Within 7d (outside 24h): 2 UP -> 7d = 3/4 = 75.0%
        insertCheckResult(monitorId, true, 200, 45, null, now.minus(3, ChronoUnit.DAYS));
        insertCheckResult(monitorId, true, 200, 42, null, now.minus(4, ChronoUnit.DAYS));
        // Within 30d (outside 7d): 1 DOWN -> 30d = 3/5 = 60.0%
        insertCheckResult(monitorId, false, 502, 100, "HTTP 502", now.minus(15, ChronoUnit.DAYS));

        mockMvc.perform(get("/api/v1/monitors/" + monitorId + "/uptime")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.last24Hours.totalChecks").value(2))
                .andExpect(jsonPath("$.last24Hours.successfulChecks").value(1))
                .andExpect(jsonPath("$.last24Hours.uptimePercentage").value(50.0))
                .andExpect(jsonPath("$.last7Days.totalChecks").value(4))
                .andExpect(jsonPath("$.last7Days.successfulChecks").value(3))
                .andExpect(jsonPath("$.last7Days.uptimePercentage").value(75.0))
                .andExpect(jsonPath("$.last30Days.totalChecks").value(5))
                .andExpect(jsonPath("$.last30Days.successfulChecks").value(3))
                .andExpect(jsonPath("$.last30Days.uptimePercentage").value(60.0));

        // Bob gets 404
        mockMvc.perform(get("/api/v1/monitors/" + monitorId + "/uptime")
                        .header("Authorization", "Bearer " + bobToken))
                .andExpect(status().isNotFound());
    }

    // ── /incidents ────────────────────────────────────────────────────────────

    @Test
    void getIncidents_returnsScopedFeeds_andEnforces404Ownership() throws Exception {
        String aliceToken = registerAndLogin("alice-inc@example.com");
        String bobToken   = registerAndLogin("bob-inc@example.com");
        Long aliceMonitor = createMonitor(aliceToken, "Alice API");
        Long bobMonitor   = createMonitor(bobToken, "Bob API");

        Long aliceUserId = userRepository.findByEmail("alice-inc@example.com").orElseThrow().getId();
        Long bobUserId   = userRepository.findByEmail("bob-inc@example.com").orElseThrow().getId();

        incidentRepository.save(Incident.open(aliceMonitor, aliceUserId, 3, "HTTP 503"));
        incidentRepository.save(Incident.open(bobMonitor, bobUserId, 3, "Timeout"));

        // Alice's /incidents feed shows only her 1 incident
        mockMvc.perform(get("/api/v1/incidents")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content", hasSize(1)))
                .andExpect(jsonPath("$.content[0].monitorId").value(aliceMonitor))
                .andExpect(jsonPath("$.content[0].status").value("OPEN"))
                .andExpect(jsonPath("$.content[0].lastError").value("HTTP 503"));

        // Alice's /monitors/{id}/incidents returns her incident
        mockMvc.perform(get("/api/v1/monitors/" + aliceMonitor + "/incidents")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1));

        // Bob querying Alice's monitor incidents gets 404
        mockMvc.perform(get("/api/v1/monitors/" + aliceMonitor + "/incidents")
                        .header("Authorization", "Bearer " + bobToken))
                .andExpect(status().isNotFound());
    }

    // ── 30-day purge job ──────────────────────────────────────────────────────

    @Test
    void purgeJob_deletesOnlyResultsOlderThan30Days() throws Exception {
        String token   = registerAndLogin("purge@example.com");
        Long monitorId = createMonitor(token, "Purge Test");

        Instant now = Instant.now();
        insertCheckResult(monitorId, true, 200, 30, null, now.minus(35, ChronoUnit.DAYS));
        insertCheckResult(monitorId, true, 200, 30, null, now.minus(1, ChronoUnit.DAYS));

        assertThat(checkResultRepository.count()).isEqualTo(2);

        purgeJob.purgeExpiredResults();

        assertThat(checkResultRepository.count()).isEqualTo(1);
    }
}
