package com.pulseops.monitor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * DB-driven scheduler that polls for monitors due for a check every 10 seconds.
 *
 * Design: single-tick polling with optimistic claims (no distributed lock needed).
 *
 * Claiming protocol:
 *   - SELECT monitors WHERE eligible LIMIT N FOR UPDATE SKIP LOCKED
 *   - SET check_status = 'RUNNING', claim_token = random UUID, claimed_until = now + 90s
 *   - Dispatch to thread pool
 *   - On completion: set check_status = 'IDLE', next_check_at = now + interval
 *
 * Crash recovery:
 *   - Any RUNNING monitor whose claimed_until has passed is eligible for reclaim
 *   - This handles JVM crashes, OOM kills, and unhandled exceptions in the worker
 *
 * Phase 5 hook: processResult() has a clearly marked section for incident detection.
 * The scheduler does NOT throw from that point — incident logic failures must not
 * prevent the monitor from being reset to IDLE.
 */
@Component
public class MonitorScheduler {

    private static final Logger log = LoggerFactory.getLogger(MonitorScheduler.class);

    /** Lease duration — if a worker holds a monitor longer than this, it is considered crashed. */
    private static final int LEASE_SECONDS = 90;

    /** How many monitors to claim per tick — prevents one tick from starving others. */
    private static final int BATCH_SIZE = 10;

    private final MonitorRepository      monitorRepository;
    private final CheckResultRepository  checkResultRepository;
    private final HttpCheckExecutor      executor;

    /** Bounded thread pool — prevents unbounded parallelism under load. */
    private final ExecutorService threadPool;

    public MonitorScheduler(MonitorRepository monitorRepository,
                            CheckResultRepository checkResultRepository,
                            HttpCheckExecutor executor) {
        this.monitorRepository     = monitorRepository;
        this.checkResultRepository = checkResultRepository;
        this.executor              = executor;
        this.threadPool            = Executors.newFixedThreadPool(10);
    }

    // ── Scheduler tick ────────────────────────────────────────────────────────

    /**
     * Runs every 10 seconds.
     * Claims due monitors and dispatches them to the thread pool.
     * Does NOT block waiting for checks to complete.
     */
    @Scheduled(fixedDelay = 10_000)
    public void tick() {
        List<Monitor> claimed = claimDueMonitors();
        for (Monitor monitor : claimed) {
            threadPool.submit(() -> runCheck(monitor));
        }
    }

    // ── Claim ─────────────────────────────────────────────────────────────────

    /**
     * Claims up to BATCH_SIZE monitors in a single transaction.
     * Includes both normally-due monitors (IDLE, next_check_at <= now)
     * and crash-recovered monitors (RUNNING, claimed_until <= now).
     */
    @Transactional
    public List<Monitor> claimDueMonitors() {
        Instant now = Instant.now();
        Instant lease = now.plusSeconds(LEASE_SECONDS);
        UUID token = UUID.randomUUID();

        // Load candidates: due IDLE monitors + expired RUNNING (crashed workers)
        List<Monitor> candidates = monitorRepository
                .findClaimCandidates(now, BATCH_SIZE);

        for (Monitor m : candidates) {
            m.setCheckStatus(Monitor.CheckStatus.RUNNING);
            m.setClaimToken(token);
            m.setClaimedUntil(lease);
        }

        return monitorRepository.saveAll(candidates);
    }

    // ── Execute + process ─────────────────────────────────────────────────────

    /** Run the HTTP check and persist the result. Called from the thread pool. */
    private void runCheck(Monitor monitor) {
        CheckResult result = executor.execute(monitor);
        try {
            processResult(monitor, result);
        } catch (Exception e) {
            log.error("Error processing result for monitor {}: {}", monitor.getId(), e.getMessage(), e);
            // Still reset the monitor to IDLE so the scheduler doesn't consider it stuck
            safeReset(monitor);
        }
    }

    @Transactional
    public void processResult(Monitor monitor, CheckResult result) {
        // 1. Persist the check result
        checkResultRepository.save(result);

        // 2. Update denormalized snapshot on the monitor row (powers fast list queries)
        monitor.setLastCheckedAt(result.getCheckedAt());
        monitor.setLastResponseMs(result.getLatencyMs());

        if (result.isSuccess()) {
            monitor.setLastStatus(Monitor.LastStatus.UP);
            monitor.setConsecutiveFailures(0);
        } else {
            monitor.setLastStatus(Monitor.LastStatus.DOWN);
            monitor.setConsecutiveFailures(monitor.getConsecutiveFailures() + 1);
        }

        // ── Phase 5 hook ─────────────────────────────────────────────────────
        // TODO: incident detection will go here.
        // if (shouldOpenIncident(monitor)) incidentService.openIncident(monitor);
        // if (shouldCloseIncident(monitor)) incidentService.closeIncident(monitor);
        // ─────────────────────────────────────────────────────────────────────

        // 3. Release the lease and schedule the next check
        monitor.setCheckStatus(Monitor.CheckStatus.IDLE);
        monitor.setClaimToken(null);
        monitor.setClaimedUntil(null);
        monitor.setNextCheckAt(Instant.now().plusSeconds(monitor.getIntervalMinutes() * 60L));

        monitorRepository.save(monitor);
    }

    @Transactional
    public void safeReset(Monitor monitor) {
        try {
            monitor.setCheckStatus(Monitor.CheckStatus.IDLE);
            monitor.setClaimToken(null);
            monitor.setClaimedUntil(null);
            monitor.setNextCheckAt(Instant.now().plusSeconds(monitor.getIntervalMinutes() * 60L));
            monitorRepository.save(monitor);
        } catch (Exception e) {
            log.error("Failed to reset monitor {} to IDLE: {}", monitor.getId(), e.getMessage());
        }
    }
}
