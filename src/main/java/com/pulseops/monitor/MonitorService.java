package com.pulseops.monitor;

import com.pulseops.exception.QuotaExceededException;
import com.pulseops.exception.ResourceNotFoundException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

@Service
@Transactional
public class MonitorService {

    static final int MAX_MONITORS_PER_USER = 20;

    private final MonitorRepository monitorRepository;
    private final CheckResultRepository checkResultRepository;
    private final SsrfValidator ssrfValidator;

    public MonitorService(MonitorRepository monitorRepository,
                          CheckResultRepository checkResultRepository,
                          SsrfValidator ssrfValidator) {
        this.monitorRepository     = monitorRepository;
        this.checkResultRepository = checkResultRepository;
        this.ssrfValidator         = ssrfValidator;
    }

    // ── Create ────────────────────────────────────────────────────────────────

    /**
     * Create a new monitor for the given user.
     *
     * Order of checks (fail-fast, cheapest first):
     *   1. Quota (count query — no DNS I/O)
     *   2. SSRF validation (DNS resolution — network I/O)
     *   3. Save
     */
    public MonitorResponse create(Long userId, CreateMonitorRequest req) {
        enforceQuota(userId);
        ssrfValidator.validate(req.url());   // throws SsrfException (422) if blocked

        Monitor monitor = new Monitor();
        monitor.setUserId(userId);
        applyRequest(monitor, req);

        return MonitorResponse.from(monitorRepository.save(monitor));
    }

    // ── Read ──────────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public Page<MonitorResponse> list(Long userId, Pageable pageable) {
        return monitorRepository.findByUserId(userId, pageable)
                .map(MonitorResponse::from);
    }

    @Transactional(readOnly = true)
    public MonitorResponse getById(Long id, Long userId) {
        return MonitorResponse.from(findOwned(id, userId));
    }

    /**
     * Paginated check history for an owned monitor (newest first).
     * Backed by idx_cr_monitor_checked.
     */
    @Transactional(readOnly = true)
    public Page<CheckResultResponse> getResults(Long id, Long userId, Pageable pageable) {
        findOwned(id, userId); // 404 if missing or not owned by caller
        return checkResultRepository.findByMonitorIdOrderByCheckedAtDesc(id, pageable)
                .map(CheckResultResponse::from);
    }

    /**
     * Compute uptime % across 24h, 7d, and 30d windows for an owned monitor.
     */
    @Transactional(readOnly = true)
    public UptimeResponse getUptime(Long id, Long userId) {
        findOwned(id, userId); // 404 if missing or not owned by caller
        Instant now = Instant.now();
        return new UptimeResponse(
                id,
                computeWindow(id, now.minus(24, ChronoUnit.HOURS)),
                computeWindow(id, now.minus(7, ChronoUnit.DAYS)),
                computeWindow(id, now.minus(30, ChronoUnit.DAYS))
        );
    }

    private UptimeResponse.WindowStats computeWindow(Long monitorId, Instant since) {
        long total = checkResultRepository.countByMonitorIdAndCheckedAtAfter(monitorId, since);
        if (total == 0) {
            return UptimeResponse.WindowStats.of(0, 0);
        }
        long successful = checkResultRepository
                .countByMonitorIdAndSuccessAndCheckedAtAfter(monitorId, true, since);
        return UptimeResponse.WindowStats.of(total, successful);
    }

    // ── Update ────────────────────────────────────────────────────────────────

    /**
     * Full replacement (PUT semantics — all fields required).
     * Re-validates SSRF in case the URL changed.
     * Does NOT reset consecutiveFailures or scheduler state.
     */
    public MonitorResponse update(Long id, Long userId, CreateMonitorRequest req) {
        Monitor monitor = findOwned(id, userId);
        ssrfValidator.validate(req.url());
        applyRequest(monitor, req);
        return MonitorResponse.from(monitorRepository.save(monitor));
    }

    // ── Delete ────────────────────────────────────────────────────────────────

    /** Hard delete — cascades check_results and incidents at DB level. */
    public void delete(Long id, Long userId) {
        Monitor monitor = findOwned(id, userId);
        monitorRepository.delete(monitor);
    }

    // ── Pause / Resume ────────────────────────────────────────────────────────

    /**
     * Pause: stop scheduling checks.
     * Open incidents are left open (spec §2.5).
     */
    public MonitorResponse pause(Long id, Long userId) {
        Monitor monitor = findOwned(id, userId);
        monitor.setEnabled(false);
        return MonitorResponse.from(monitorRepository.save(monitor));
    }

    /**
     * Resume: re-enable scheduling and trigger an immediate check
     * by resetting nextCheckAt to now().
     */
    public MonitorResponse resume(Long id, Long userId) {
        Monitor monitor = findOwned(id, userId);
        monitor.setEnabled(true);
        monitor.setNextCheckAt(Instant.now());   // scheduler picks it up on next tick
        return MonitorResponse.from(monitorRepository.save(monitor));
    }

    // ── Internal helpers ──────────────────────────────────────────────────────

    /**
     * Load a monitor that belongs to userId.
     * Returns 404 (not 403) if not found OR if owned by someone else —
     * the caller never learns whether the ID exists for another user.
     */
    private Monitor findOwned(Long id, Long userId) {
        return monitorRepository.findByIdAndUserId(id, userId)
                .orElseThrow(() -> new ResourceNotFoundException("Monitor not found"));
    }

    private void enforceQuota(Long userId) {
        long count = monitorRepository.countByUserId(userId);
        if (count >= MAX_MONITORS_PER_USER) {
            throw new QuotaExceededException(
                    "Monitor limit reached. Maximum " + MAX_MONITORS_PER_USER + " monitors per account.");
        }
    }

    /** Apply all mutable fields from a create/update request to the entity. */
    private void applyRequest(Monitor monitor, CreateMonitorRequest req) {
        monitor.setName(req.name());
        monitor.setUrl(req.url());
        monitor.setIntervalMinutes(req.resolvedIntervalMinutes());
        monitor.setTimeoutSeconds(req.resolvedTimeoutSeconds());
        monitor.setFailureThreshold(req.resolvedFailureThreshold());
    }
}
