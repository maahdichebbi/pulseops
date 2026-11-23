package com.pulseops.monitor;

import jakarta.persistence.*;
import java.time.Instant;

/**
 * One row per HTTP check attempt.
 * Owned by a monitor (cascades on delete).
 *
 * No Spring auditing needed — checked_at is set at construction time
 * and never changes after insert (@Immutable would be ideal; left off
 * to keep the class simple since we never update these rows).
 */
@Entity
@Table(name = "check_results")
public class CheckResult {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** FK to monitors(id) ON DELETE CASCADE — set once, never changed. */
    @Column(name = "monitor_id", nullable = false, updatable = false)
    private Long monitorId;

    /** true = 2xx/3xx final response; false = 4xx/5xx/timeout/network error. */
    @Column(nullable = false)
    private boolean success;

    /** HTTP status code of the final response. NULL when there was no HTTP response (timeout, DNS failure). */
    @Column(name = "status_code")
    private Integer statusCode;

    /** Total time from request start to response body complete, in milliseconds. NULL on network error. */
    @Column(name = "latency_ms")
    private Integer latencyMs;

    /** Human-readable failure reason. NULL on success. Max 500 chars (truncated if longer). */
    @Column(name = "error_detail", length = 500)
    private String errorDetail;

    @Column(name = "checked_at", nullable = false, updatable = false)
    private Instant checkedAt;

    @PrePersist
    void onPrePersist() {
        if (checkedAt == null) {
            checkedAt = Instant.now();
        }
    }

    // ── Factory methods ───────────────────────────────────────────────────────

    public static CheckResult success(Long monitorId, int statusCode, int latencyMs) {
        CheckResult r = new CheckResult();
        r.monitorId  = monitorId;
        r.success    = true;
        r.statusCode = statusCode;
        r.latencyMs  = latencyMs;
        return r;
    }

    public static CheckResult failure(Long monitorId, Integer statusCode, Integer latencyMs, String errorDetail) {
        CheckResult r = new CheckResult();
        r.monitorId   = monitorId;
        r.success     = false;
        r.statusCode  = statusCode;
        r.latencyMs   = latencyMs;
        r.errorDetail = errorDetail != null && errorDetail.length() > 500
                ? errorDetail.substring(0, 500)
                : errorDetail;
        return r;
    }

    // ── Getters ───────────────────────────────────────────────────────────────

    public Long    getId()          { return id; }
    public Long    getMonitorId()   { return monitorId; }
    public boolean isSuccess()      { return success; }
    public Integer getStatusCode()  { return statusCode; }
    public Integer getLatencyMs()   { return latencyMs; }
    public String  getErrorDetail() { return errorDetail; }
    public Instant getCheckedAt()   { return checkedAt; }
}
