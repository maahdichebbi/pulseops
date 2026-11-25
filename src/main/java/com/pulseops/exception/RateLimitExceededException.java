package com.pulseops.exception;

/**
 * Thrown when a client exceeds the allowed request rate (e.g. login brute-force protection).
 * Mapped to HTTP 429 Too Many Requests by GlobalExceptionHandler.
 */
public class RateLimitExceededException extends RuntimeException {
    public RateLimitExceededException(String message) {
        super(message);
    }
}
