package com.pulseops.monitor;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;

public interface CheckResultRepository extends JpaRepository<CheckResult, Long> {

    /**
     * Paginated check history for a monitor.
     * Used by GET /api/v1/monitors/{id}/results (Phase 6).
     * idx_cr_monitor_checked covers this query.
     */
    Page<CheckResult> findByMonitorIdOrderByCheckedAtDesc(Long monitorId, Pageable pageable);

    /**
     * Count results in a time window — used for uptime % calculation.
     * Example: total checks in last 24h for a monitor.
     */
    long countByMonitorIdAndCheckedAtAfter(Long monitorId, Instant since);

    /**
     * Count successful results in a time window.
     * Uptime % = countSuccessful / countTotal * 100.
     */
    long countByMonitorIdAndSuccessAndCheckedAtAfter(Long monitorId, boolean success, Instant since);

    /**
     * Delete results older than 30 days.
     * Called by the daily purge job (Phase 6).
     * idx_cr_checked_at covers this DELETE without a full scan.
     */
    long deleteByCheckedAtBefore(Instant cutoff);
}
