package com.pulseops.auth;

import com.pulseops.exception.RateLimitExceededException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory per-IP sliding-window rate limiter for POST /api/v1/auth/login.
 *
 * Default: max 5 attempts per 60 seconds per IP.
 * Protects against brute-force / credential-stuffing attacks without requiring Redis.
 * Configurable via app.rate-limit.login.* properties (disabled in general test profile
 * so multi-login integration tests don't trip it, and explicitly tested in isolation).
 */
@Component
public class LoginRateLimiter {

    private final boolean enabled;
    private final int maxAttempts;
    private final Duration window;
    private final Clock clock;

    private final ConcurrentHashMap<String, Deque<Instant>> attemptsByIp = new ConcurrentHashMap<>();

    @Autowired
    public LoginRateLimiter(
            @Value("${app.rate-limit.login.enabled:true}") boolean enabled,
            @Value("${app.rate-limit.login.max-attempts:5}") int maxAttempts,
            @Value("${app.rate-limit.login.window-seconds:60}") long windowSeconds) {
        this(enabled, maxAttempts, Duration.ofSeconds(windowSeconds), Clock.systemUTC());
    }

    /** Package-private constructor for deterministic unit tests with a controllable Clock. */
    LoginRateLimiter(boolean enabled, int maxAttempts, Duration window, Clock clock) {
        this.enabled     = enabled;
        this.maxAttempts = maxAttempts;
        this.window      = window;
        this.clock       = clock;
    }

    /**
     * Record a login attempt for {@code clientIp} and throw {@link RateLimitExceededException}
     * if the sliding-window limit has been reached.
     */
    public void checkAllowed(String clientIp) {
        if (!enabled) {
            return;
        }

        String key = (clientIp == null || clientIp.isBlank()) ? "unknown" : clientIp;
        Instant now = clock.instant();
        Instant cutoff = now.minus(window);

        Deque<Instant> timestamps = attemptsByIp.computeIfAbsent(key, k -> new ArrayDeque<>());
        synchronized (timestamps) {
            while (!timestamps.isEmpty() && !timestamps.peekFirst().isAfter(cutoff)) {
                timestamps.pollFirst();
            }

            if (timestamps.size() >= maxAttempts) {
                throw new RateLimitExceededException(
                        "Too many login attempts. Please try again later.");
            }

            timestamps.addLast(now);
        }
    }
}
