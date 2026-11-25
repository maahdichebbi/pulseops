package com.pulseops.auth;

import com.pulseops.exception.RateLimitExceededException;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LoginRateLimiterTest {

    private static class MutableClock extends Clock {
        private Instant instant;

        MutableClock(Instant initial) {
            this.instant = initial;
        }

        void advance(Duration duration) {
            this.instant = this.instant.plus(duration);
        }

        @Override public ZoneId getZone() { return ZoneId.of("UTC"); }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return instant; }
    }

    @Test
    void checkAllowed_allows5Attempts_blocks6thWithinWindow() {
        MutableClock clock = new MutableClock(Instant.parse("2026-11-25T12:00:00Z"));
        LoginRateLimiter limiter = new LoginRateLimiter(true, 5, Duration.ofSeconds(60), clock);

        for (int i = 0; i < 5; i++) {
            assertThatCode(() -> limiter.checkAllowed("1.2.3.4"))
                    .doesNotThrowAnyException();
        }

        assertThatThrownBy(() -> limiter.checkAllowed("1.2.3.4"))
                .isInstanceOf(RateLimitExceededException.class)
                .hasMessageContaining("Too many login attempts");
    }

    @Test
    void checkAllowed_tracksDifferentIpsIndependently() {
        MutableClock clock = new MutableClock(Instant.parse("2026-11-25T12:00:00Z"));
        LoginRateLimiter limiter = new LoginRateLimiter(true, 5, Duration.ofSeconds(60), clock);

        for (int i = 0; i < 5; i++) {
            limiter.checkAllowed("1.2.3.4");
        }

        // Different IP is unaffected
        assertThatCode(() -> limiter.checkAllowed("5.6.7.8"))
                .doesNotThrowAnyException();
    }

    @Test
    void checkAllowed_allowsAgainAfterWindowExpires() {
        MutableClock clock = new MutableClock(Instant.parse("2026-11-25T12:00:00Z"));
        LoginRateLimiter limiter = new LoginRateLimiter(true, 5, Duration.ofSeconds(60), clock);

        for (int i = 0; i < 5; i++) {
            limiter.checkAllowed("1.2.3.4");
        }
        assertThatThrownBy(() -> limiter.checkAllowed("1.2.3.4"))
                .isInstanceOf(RateLimitExceededException.class);

        // Advance past the 60-second window
        clock.advance(Duration.ofSeconds(61));

        assertThatCode(() -> limiter.checkAllowed("1.2.3.4"))
                .doesNotThrowAnyException();
    }
}
