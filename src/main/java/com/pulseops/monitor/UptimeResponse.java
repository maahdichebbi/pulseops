package com.pulseops.monitor;

/**
 * Uptime summary across 24h, 7d, and 30d windows for a single monitor.
 * Returned by GET /api/v1/monitors/{id}/uptime.
 */
public record UptimeResponse(
        Long monitorId,
        WindowStats last24Hours,
        WindowStats last7Days,
        WindowStats last30Days
) {
    /**
     * Stats for one time window.
     * uptimePercentage is null when totalChecks == 0 so callers can distinguish
     * "no checks yet" from 0% or 100%.
     */
    public record WindowStats(
            long totalChecks,
            long successfulChecks,
            Double uptimePercentage
    ) {
        public static WindowStats of(long totalChecks, long successfulChecks) {
            if (totalChecks == 0) {
                return new WindowStats(0, 0, null);
            }
            double pct = Math.round((successfulChecks * 10000.0) / totalChecks) / 100.0;
            return new WindowStats(totalChecks, successfulChecks, pct);
        }
    }
}
