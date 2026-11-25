package com.pulseops.monitor;

import java.time.Instant;

/**
 * Response DTO for a single HTTP check attempt.
 * Returned by GET /api/v1/monitors/{id}/results.
 */
public record CheckResultResponse(
        Long id,
        Long monitorId,
        boolean success,
        Integer statusCode,
        Integer latencyMs,
        String errorDetail,
        Instant checkedAt
) {
    public static CheckResultResponse from(CheckResult r) {
        return new CheckResultResponse(
                r.getId(),
                r.getMonitorId(),
                r.isSuccess(),
                r.getStatusCode(),
                r.getLatencyMs(),
                r.getErrorDetail(),
                r.getCheckedAt()
        );
    }
}
