package com.pulseops.monitor;

import jakarta.persistence.*;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.Instant;
import java.util.UUID;

/**
 * JPA entity mapping to the `monitors` table (Flyway V2).
 *
 * Design decisions:
 * - userId is a plain Long, NOT a @ManyToOne User join.
 *   Reason: monitors are queried by userId constantly; a lazy join would fire
 *   an extra query on every load for no benefit. Keeping it as a FK-constrained
 *   Long is cleaner and faster.
 *
 * - CheckStatus and LastStatus are Java enums stored as VARCHAR (EnumType.STRING).
 *   This gives compile-time safety in Java while remaining readable in the DB.
 *   The DB also has CHECK constraints as a last-line defence.
 *
 * - @CreatedDate sets createdAt once at INSERT time (updatable=false).
 * - @LastModifiedDate updates updatedAt on every UPDATE automatically.
 *   Both are powered by @EnableJpaAuditing in JpaConfig.
 */
@Entity
@Table(name = "monitors")
@EntityListeners(AuditingEntityListener.class)
public class Monitor {

    // ── Enums ─────────────────────────────────────────────────────────────────

    public enum CheckStatus { IDLE, RUNNING }
    public enum LastStatus  { UP, DOWN }

    // ── Identity ──────────────────────────────────────────────────────────────

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Owner's user ID — FK to users(id) ON DELETE CASCADE defined in V2. */
    @Column(name = "user_id", nullable = false, updatable = false)
    private Long userId;

    // ── Core config ───────────────────────────────────────────────────────────

    @Column(nullable = false, length = 100)
    private String name;

    @Column(nullable = false, length = 2048)
    private String url;

    @Column(name = "interval_minutes", nullable = false)
    private int intervalMinutes = 5;

    @Column(name = "timeout_seconds", nullable = false)
    private int timeoutSeconds = 10;

    @Column(name = "failure_threshold", nullable = false)
    private int failureThreshold = 3;

    // ── Runtime state ─────────────────────────────────────────────────────────

    @Column(name = "consecutive_failures", nullable = false)
    private int consecutiveFailures = 0;

    @Enumerated(EnumType.STRING)
    @Column(name = "check_status", nullable = false, length = 10)
    private CheckStatus checkStatus = CheckStatus.IDLE;

    /** Lease expiry timestamp — NULL when IDLE, set when RUNNING. */
    @Column(name = "claimed_until")
    private Instant claimedUntil;

    /** Random UUID issued at claim time — NULL when IDLE. */
    @Column(name = "claim_token")
    private UUID claimToken;

    @Column(nullable = false)
    private boolean enabled = true;

    /** Next scheduled check time. Reset to now() when a monitor is resumed. */
    @Column(name = "next_check_at", nullable = false)
    private Instant nextCheckAt;

    // ── Last check snapshot (denormalized for fast list queries) ─────────────

    /** NULL until the first check completes. */
    @Enumerated(EnumType.STRING)
    @Column(name = "last_status", length = 10)
    private LastStatus lastStatus;

    @Column(name = "last_checked_at")
    private Instant lastCheckedAt;

    @Column(name = "last_response_ms")
    private Integer lastResponseMs;

    // ── Target GeoIP metadata (optional, populated on create/update) ────────

    @Column(name = "target_ip", length = 45)
    private String targetIp;

    @Column(name = "geo_country", length = 100)
    private String geoCountry;

    @Column(name = "geo_city", length = 100)
    private String geoCity;

    @Column(name = "geo_lat")
    private Double geoLat;

    @Column(name = "geo_lon")
    private Double geoLon;

    // ── Audit timestamps ──────────────────────────────────────────────────────

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    /** Set nextCheckAt to now on first persist so the scheduler picks it up immediately. */
    @PrePersist
    void onPrePersist() {
        if (nextCheckAt == null) {
            nextCheckAt = Instant.now();
        }
    }

    // ── Getters ───────────────────────────────────────────────────────────────

    public Long getId()                 { return id; }
    public Long getUserId()             { return userId; }
    public String getName()             { return name; }
    public String getUrl()              { return url; }
    public int getIntervalMinutes()     { return intervalMinutes; }
    public int getTimeoutSeconds()      { return timeoutSeconds; }
    public int getFailureThreshold()    { return failureThreshold; }
    public int getConsecutiveFailures() { return consecutiveFailures; }
    public CheckStatus getCheckStatus() { return checkStatus; }
    public Instant getClaimedUntil()    { return claimedUntil; }
    public UUID getClaimToken()         { return claimToken; }
    public boolean isEnabled()          { return enabled; }
    public Instant getNextCheckAt()     { return nextCheckAt; }
    public LastStatus getLastStatus()   { return lastStatus; }
    public Instant getLastCheckedAt()   { return lastCheckedAt; }
    public Integer getLastResponseMs()  { return lastResponseMs; }
    public String getTargetIp()         { return targetIp; }
    public String getGeoCountry()       { return geoCountry; }
    public String getGeoCity()          { return geoCity; }
    public Double getGeoLat()           { return geoLat; }
    public Double getGeoLon()           { return geoLon; }
    public Instant getCreatedAt()       { return createdAt; }
    public Instant getUpdatedAt()       { return updatedAt; }

    // ── Setters (only mutable fields) ─────────────────────────────────────────

    public void setUserId(Long userId)                       { this.userId = userId; }
    public void setName(String name)                         { this.name = name; }
    public void setUrl(String url)                           { this.url = url; }
    public void setIntervalMinutes(int intervalMinutes)      { this.intervalMinutes = intervalMinutes; }
    public void setTimeoutSeconds(int timeoutSeconds)        { this.timeoutSeconds = timeoutSeconds; }
    public void setFailureThreshold(int failureThreshold)    { this.failureThreshold = failureThreshold; }
    public void setConsecutiveFailures(int n)                { this.consecutiveFailures = n; }
    public void setCheckStatus(CheckStatus checkStatus)      { this.checkStatus = checkStatus; }
    public void setClaimedUntil(Instant claimedUntil)        { this.claimedUntil = claimedUntil; }
    public void setClaimToken(UUID claimToken)               { this.claimToken = claimToken; }
    public void setEnabled(boolean enabled)                  { this.enabled = enabled; }
    public void setNextCheckAt(Instant nextCheckAt)          { this.nextCheckAt = nextCheckAt; }
    public void setLastStatus(LastStatus lastStatus)         { this.lastStatus = lastStatus; }
    public void setLastCheckedAt(Instant lastCheckedAt)      { this.lastCheckedAt = lastCheckedAt; }
    public void setLastResponseMs(Integer lastResponseMs)    { this.lastResponseMs = lastResponseMs; }
    public void setTargetIp(String targetIp)                 { this.targetIp = targetIp; }
    public void setGeoCountry(String geoCountry)             { this.geoCountry = geoCountry; }
    public void setGeoCity(String geoCity)                   { this.geoCity = geoCity; }
    public void setGeoLat(Double geoLat)                     { this.geoLat = geoLat; }
    public void setGeoLon(Double geoLon)                     { this.geoLon = geoLon; }
}
