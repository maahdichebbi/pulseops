package com.pulseops.exception;

public class SsrfException extends RuntimeException {
    public SsrfException(String message) {
        super(message);
    }
}
