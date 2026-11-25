package com.pulseops.monitor;

import jakarta.validation.constraints.*;

/**
 * Request body for POST /api/v1/monitors (create) and
 * PUT /api/v1/monitors/{id} (full replacement).
 *
 * Bean Validation runs first (fast, no I/O).
 * SSRF validation runs in the service layer (requires DNS resolution).
 */
public record CreateMonitorRequest(

        @NotBlank(message = "Name is required")
        @Size(max = 100, message = "Name must be 100 characters or fewer")
        String name,

        @NotBlank(message = "URL is required")
        @Size(max = 2048, message = "URL must be 2048 characters or fewer")
        String url,

        @Min(value = 1,  message = "Interval must be between 1 and 60 minutes")
        @Max(value = 60, message = "Interval must be between 1 and 60 minutes")
        Integer intervalMinutes,

        @Min(value = 1,  message = "Timeout must be between 1 and 30 seconds")
        @Max(value = 30, message = "Timeout must be between 1 and 30 seconds")
        Integer timeoutSeconds,

        @Min(value = 1,  message = "Failure threshold must be between 1 and 10")
        @Max(value = 10, message = "Failure threshold must be between 1 and 10")
        Integer failureThreshold
) {
    /** Apply defaults for optional fields. */
    public int resolvedIntervalMinutes()  { return intervalMinutes  != null ? intervalMinutes  : 5;  }
    public int resolvedTimeoutSeconds()   { return timeoutSeconds   != null ? timeoutSeconds   : 10; }
    public int resolvedFailureThreshold() { return failureThreshold != null ? failureThreshold : 3;  }
}
