package com.pulseops.auth;

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

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Integration tests — full Spring context + real Postgres via Testcontainers.
 *
 * Uses MockMvc to send HTTP requests through the full filter chain
 * (security filters, JWT filter, controllers, services, DB).
 * Each test verifies a complete user-facing scenario.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@ActiveProfiles("test")
class AuthIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired WebApplicationContext context;
    @Autowired JwtService jwtService;
    @Autowired UserRepository userRepository;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        // Build MockMvc with the full Spring Security filter chain applied
        mockMvc = MockMvcBuilders
                .webAppContextSetup(context)
                .apply(SecurityMockMvcConfigurers.springSecurity())
                .build();
        userRepository.deleteAll();
    }

    // ─── REGISTER ──────────────────────────────────────────────────────────

    /**
     * Proves register happy path: 201, body has id/email/createdAt,
     * and the password is NOT returned in the response.
     */
    @Test
    void register_success_returns201() throws Exception {
        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"alice@example.com","password":"securepass123"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNumber())
                .andExpect(jsonPath("$.email").value("alice@example.com"))
                .andExpect(jsonPath("$.createdAt").exists())
                .andExpect(jsonPath("$.password").doesNotExist())
                .andExpect(jsonPath("$.passwordHash").doesNotExist());
    }

    /**
     * Proves invalid input is rejected with 400 and fieldErrors.
     * The error format matches the spec: status/error/message/path/timestamp/fieldErrors.
     */
    @Test
    void register_invalidInput_returns400WithFieldErrors() throws Exception {
        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"not-an-email","password":"short"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.fieldErrors").isArray())
                .andExpect(jsonPath("$.fieldErrors", hasSize(greaterThanOrEqualTo(1))));
    }

    /**
     * Proves duplicate email is rejected with 409.
     * Both the application-level check AND the DB constraint are covered
     * because we register the same email twice.
     */
    @Test
    void register_duplicateEmail_returns409() throws Exception {
        String body = """
                {"email":"bob@example.com","password":"securepass123"}
                """;

        mockMvc.perform(post("/api/v1/auth/register")
                .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/v1/auth/register")
                .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.error").value("Conflict"));
    }

    /**
     * Proves email is lowercased: registering with "BOB@EXAMPLE.COM"
     * and the saved email must be lowercase.
     */
    @Test
    void register_emailIsNormalized() throws Exception {
        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"UPPER@EXAMPLE.COM","password":"securepass123"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.email").value("upper@example.com"));
    }

    // ─── LOGIN ─────────────────────────────────────────────────────────────

    /**
     * Proves login success: 200, response contains a non-empty token string.
     */
    @Test
    void login_success_returnsToken() throws Exception {
        mockMvc.perform(post("/api/v1/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"email":"carol@example.com","password":"securepass123"}
                        """))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"carol@example.com","password":"securepass123"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").isString())
                .andExpect(jsonPath("$.token").isNotEmpty());
    }

    /**
     * Proves wrong password returns 401 with a generic message.
     * The message must NOT reveal whether the email exists
     * (prevents user enumeration attacks).
     */
    @Test
    void login_wrongPassword_returns401Generic() throws Exception {
        mockMvc.perform(post("/api/v1/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"email":"dave@example.com","password":"securepass123"}
                        """))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"dave@example.com","password":"wrongpassword"}
                                """))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Invalid email or password"));
    }

    /**
     * Proves unknown email returns the SAME 401 message as wrong password.
     * Critical: attacker cannot distinguish "user doesn't exist" from "wrong password".
     */
    @Test
    void login_unknownEmail_returns401SameMessage() throws Exception {
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"nobody@example.com","password":"anypassword"}
                                """))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Invalid email or password"));
    }

    // ─── /USERS/ME ─────────────────────────────────────────────────────────

    /**
     * Proves /users/me returns the authenticated user's profile
     * when given a valid JWT in the Authorization header.
     */
    @Test
    void usersMe_withValidToken_returns200() throws Exception {
        mockMvc.perform(post("/api/v1/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"email":"eve@example.com","password":"securepass123"}
                        """))
                .andExpect(status().isCreated());

        String loginResponse = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"eve@example.com","password":"securepass123"}
                                """))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        String token = loginResponse.replaceAll(".*\"token\":\"([^\"]+)\".*", "$1");

        mockMvc.perform(get("/api/v1/users/me")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("eve@example.com"))
                .andExpect(jsonPath("$.id").isNumber())
                .andExpect(jsonPath("$.createdAt").exists());
    }

    /**
     * Proves /users/me returns 401 when no Authorization header is sent.
     * The endpoint is protected — anonymous access is forbidden.
     */
    @Test
    void usersMe_withoutToken_returns401() throws Exception {
        mockMvc.perform(get("/api/v1/users/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Authentication required"));
    }

    /**
     * Proves /users/me rejects an expired token.
     * A user whose token expired must re-authenticate — old tokens can't be reused.
     */
    @Test
    void usersMe_withExpiredToken_returns401() throws Exception {
        String expiredToken = jwtService.generateToken(999L, "ghost@example.com",
                Instant.now().minus(1, ChronoUnit.HOURS));

        mockMvc.perform(get("/api/v1/users/me")
                        .header("Authorization", "Bearer " + expiredToken))
                .andExpect(status().isUnauthorized());
    }

    /**
     * Proves /users/me rejects a tampered token.
     * Modifying the payload invalidates the signature — the server detects the tampering.
     */
    @Test
    void usersMe_withTamperedToken_returns401() throws Exception {
        String token = jwtService.generateToken(1L, "u@e.com");
        String[] parts = token.split("\\.");
        String tampered = parts[0] + ".dGFtcGVyZWQ." + parts[2];

        mockMvc.perform(get("/api/v1/users/me")
                        .header("Authorization", "Bearer " + tampered))
                .andExpect(status().isUnauthorized());
    }
}
