package com.pulseops.incident;

import java.time.Instant;

/**
 * Response DTO for an incident.
 * Returned by GET /api/v1/incidents and GET /api/v1/monitors/{id}/incidents.
 */
public record IncidentResponse(
        Long id,
        Long monitorId,
        Incident.Status status,
        Instant openedAt,
        Instant resolvedAt,
        int failureCountAtOpen,
        String lastError
) {
    public static IncidentResponse from(Incident i) {
        return new IncidentResponse(
                i.getId(),
                i.getMonitorId(),
                i.getStatus(),
                i.getOpenedAt(),
                i.getResolvedAt(),
                i.getFailureCountAtOpen(),
                i.getLastError()
        );
    }
}
