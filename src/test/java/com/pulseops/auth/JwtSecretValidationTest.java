package com.pulseops.auth;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Proves that JwtService refuses to be constructed with a weak secret.
 *
 * This is a startup guard: misconfigured deployments fail immediately
 * rather than silently accepting a weak secret and issuing insecure tokens.
 * No Spring context or database needed — JwtService is instantiated directly.
 */
class JwtSecretValidationTest {

    /**
     * Proves that a secret shorter than 32 bytes causes construction failure.
     * A short secret makes HS256 easy to brute-force.
     */
    @Test
    void shortSecret_preventsStartup() {
        assertThatThrownBy(() -> new JwtService("tooshort", 60))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("32 bytes");
    }

    /**
     * Proves that a blank secret causes construction failure.
     */
    @Test
    void emptySecret_preventsStartup() {
        assertThatThrownBy(() -> new JwtService("", 60))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
