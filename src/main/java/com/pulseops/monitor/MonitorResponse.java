package com.pulseops.monitor;

import java.time.Instant;

/**
 * Response body for all monitor endpoints.
 *
 * Internal scheduler fields (checkStatus, claimedUntil, claimToken,
 * consecutiveFailures, nextCheckAt) are intentionally excluded — they
 * are implementation details callers never need.
 */
public record MonitorResponse(
        Long id,
        Long userId,
        String name,
        String url,
        int intervalMinutes,
        int timeoutSeconds,
        int failureThreshold,
        boolean enabled,
        Monitor.LastStatus lastStatus,    // null until first check
        Instant lastCheckedAt,            // null until first check
        Integer lastResponseMs,           // null until first check
        String targetIp,
        String geoCountry,
        String geoCity,
        Double geoLat,
        Double geoLon,
        Instant createdAt,
        Instant updatedAt
) {
    /** Map a Monitor entity to its API response. */
    public static MonitorResponse from(Monitor m) {
        return new MonitorResponse(
                m.getId(),
                m.getUserId(),
                m.getName(),
                m.getUrl(),
                m.getIntervalMinutes(),
                m.getTimeoutSeconds(),
                m.getFailureThreshold(),
                m.isEnabled(),
                m.getLastStatus(),
                m.getLastCheckedAt(),
                m.getLastResponseMs(),
                m.getTargetIp(),
                m.getGeoCountry(),
                m.getGeoCity(),
                m.getGeoLat(),
                m.getGeoLon(),
                m.getCreatedAt(),
                m.getUpdatedAt()
        );
    }
}
