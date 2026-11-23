package com.pulseops.auth;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for RegisterRequest Bean Validation rules — no Spring context needed.
 *
 * These tests prove that our DTO rejects bad input before it ever reaches
 * the service layer or the database.
 */
class RegisterRequestValidationTest {

    private static Validator validator;

    @BeforeAll
    static void setUp() {
        validator = Validation.buildDefaultValidatorFactory().getValidator();
    }

    private Set<ConstraintViolation<RegisterRequest>> validate(String email, String password) {
        return validator.validate(new RegisterRequest(email, password));
    }

    /** Proves a fully valid input produces zero violations. */
    @Test
    void validInput_noViolations() {
        assertThat(validate("user@example.com", "securepass123")).isEmpty();
    }

    /** Proves blank email is rejected — the field is required. */
    @Test
    void blankEmail_violation() {
        assertThat(validate("", "securepass123")).isNotEmpty();
    }

    /** Proves a string that isn't an email format is rejected. */
    @Test
    void invalidEmailFormat_violation() {
        assertThat(validate("not-an-email", "securepass123")).isNotEmpty();
    }

    /** Proves blank password is rejected — the field is required. */
    @Test
    void blankPassword_violation() {
        assertThat(validate("user@example.com", "")).isNotEmpty();
    }

    /** Proves passwords shorter than 8 chars are rejected. */
    @Test
    void passwordTooShort_violation() {
        assertThat(validate("user@example.com", "short")).isNotEmpty();
    }

    /**
     * Proves passwords longer than 72 chars are rejected.
     * BCrypt silently truncates at 72 bytes — we reject early to be honest with the user.
     */
    @Test
    void passwordTooLong_violation() {
        String over72 = "a".repeat(73);
        assertThat(validate("user@example.com", over72)).isNotEmpty();
    }

    /** Proves exactly 8 chars is accepted (boundary). */
    @Test
    void passwordExactly8_valid() {
        assertThat(validate("user@example.com", "exactly8")).isEmpty();
    }

    /** Proves exactly 72 chars is accepted (boundary). */
    @Test
    void passwordExactly72_valid() {
        String exactly72 = "a".repeat(72);
        assertThat(validate("user@example.com", exactly72)).isEmpty();
    }
}
