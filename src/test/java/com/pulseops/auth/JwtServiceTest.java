package com.pulseops.auth;

import io.jsonwebtoken.JwtException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.*;

/**
 * Unit tests for JwtService — no Spring context, no DB, runs in milliseconds.
 *
 * These tests verify the cryptographic guarantees of our JWT implementation:
 * that valid tokens work, and that invalid/expired/tampered tokens are rejected.
 */
class JwtServiceTest {

    // A 32-byte secret — minimum safe size for HS256
    private static final String SECRET = "test-secret-32-bytes-minimum-pad!";
    private JwtService jwtService;

    @BeforeEach
    void setUp() {
        jwtService = new JwtService(SECRET, 60);
    }

    /**
     * Proves the happy path: generate a token, extract the userId,
     * confirm it matches what we put in.
     */
    @Test
    void validToken_extractsUserIdCorrectly() {
        String token = jwtService.generateToken(42L, "user@example.com");
        assertThat(jwtService.extractUserId(token)).isEqualTo(42L);
    }

    /**
     * Proves that an expired token is rejected.
     * Security: a user who was deleted or logged out can't use an old token forever.
     */
    @Test
    void expiredToken_throwsException() {
        // Generate a token that expired 1 second ago
        String token = jwtService.generateToken(1L, "u@e.com",
                Instant.now().minus(1, ChronoUnit.SECONDS));

        assertThatThrownBy(() -> jwtService.extractUserId(token))
                .isInstanceOf(JwtException.class);
    }

    /**
     * Proves that a tampered payload is rejected.
     * Security: an attacker can't base64-decode the payload, change userId to "999",
     * re-encode, and use it — the signature check will catch the modification.
     */
    @Test
    void tamperedToken_throwsException() {
        // Replace the payload section with garbage
        String token = jwtService.generateToken(1L, "u@e.com");
        String[] parts = token.split("\\.");
        String tampered = parts[0] + ".dGFtcGVyZWQ." + parts[2]; // fake payload

        assertThatThrownBy(() -> jwtService.extractUserId(tampered))
                .isInstanceOf(JwtException.class);
    }

    /**
     * Proves that a token signed by a different secret is rejected.
     * Security: tokens issued by a different server (or attacker) can't be used here.
     */
    @Test
    void wrongSignature_throwsException() {
        JwtService otherService = new JwtService("completely-different-secret-32b!!", 60);
        String foreignToken = otherService.generateToken(1L, "u@e.com");

        assertThatThrownBy(() -> jwtService.extractUserId(foreignToken))
                .isInstanceOf(JwtException.class);
    }

    /**
     * Proves the startup guard works: a secret shorter than 32 bytes
     * is rejected immediately at construction time, not at the first login.
     */
    @Test
    void shortSecret_throwsOnConstruction() {
        assertThatThrownBy(() -> new JwtService("tooshort", 60))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("32 bytes");
    }

    /**
     * Proves the startup guard works: a blank/empty secret is rejected.
     */
    @Test
    void blankSecret_throwsOnConstruction() {
        assertThatThrownBy(() -> new JwtService("", 60))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
