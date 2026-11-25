package com.pulseops.incident;

import jakarta.persistence.*;
import java.time.Instant;

/**
 * Represents a downtime incident for a monitor (Flyway V4).
 *
 * Lifecycle:
 * - Created as OPEN when consecutiveFailures reaches failureThreshold.
 * - Closed as RESOLVED when a subsequent check succeeds.
 * - At most one OPEN incident per monitor is enforced by uq_incidents_one_open_per_monitor.
 */
@Entity
@Table(name = "incidents")
public class Incident {

    public enum Status { OPEN, RESOLVED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "monitor_id", nullable = false, updatable = false)
    private Long monitorId;

    @Column(name = "user_id", nullable = false, updatable = false)
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Status status = Status.OPEN;

    @Column(name = "opened_at", nullable = false, updatable = false)
    private Instant openedAt;

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    @Column(name = "failure_count_at_open", nullable = false, updatable = false)
    private int failureCountAtOpen;

    @Column(name = "last_error", length = 500, updatable = false)
    private String lastError;

    @PrePersist
    void onPrePersist() {
        if (openedAt == null) {
            openedAt = Instant.now();
        }
    }

    // ── Factory & Domain methods ──────────────────────────────────────────────

    public static Incident open(Long monitorId, Long userId, int failureCount, String lastError) {
        Incident incident = new Incident();
        incident.monitorId          = monitorId;
        incident.userId             = userId;
        incident.status             = Status.OPEN;
        incident.failureCountAtOpen = failureCount;
        incident.lastError          = lastError != null && lastError.length() > 500
                ? lastError.substring(0, 500)
                : lastError;
        return incident;
    }

    /** Transition this incident from OPEN to RESOLVED. */
    public void resolve() {
        this.status     = Status.RESOLVED;
        this.resolvedAt = Instant.now();
    }

    // ── Getters ───────────────────────────────────────────────────────────────

    public Long    getId()                 { return id; }
    public Long    getMonitorId()          { return monitorId; }
    public Long    getUserId()             { return userId; }
    public Status  getStatus()             { return status; }
    public Instant getOpenedAt()           { return openedAt; }
    public Instant getResolvedAt()         { return resolvedAt; }
    public int     getFailureCountAtOpen() { return failureCountAtOpen; }
    public String  getLastError()          { return lastError; }
}
