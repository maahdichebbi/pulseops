package com.pulseops.exception;

/**
 * Thrown for invalid login credentials.
 * Uses the same generic message regardless of whether the email exists
 * — prevents user enumeration.
 */
public class InvalidCredentialsException extends RuntimeException {

    public InvalidCredentialsException() {
        super("Invalid email or password");
    }
}
