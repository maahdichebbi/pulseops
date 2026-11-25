package com.pulseops.monitor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * Daily retention job that deletes check_results older than 30 days.
 *
 * Uses idx_cr_checked_at (V3) so Postgres removes expired rows without a full table scan.
 * Longest uptime window is 30 days, so older raw check rows are no longer needed.
 */
@Component
public class CheckResultPurgeJob {

    private static final Logger log = LoggerFactory.getLogger(CheckResultPurgeJob.class);
    static final int RETENTION_DAYS = 30;

    private final CheckResultRepository checkResultRepository;

    public CheckResultPurgeJob(CheckResultRepository checkResultRepository) {
        this.checkResultRepository = checkResultRepository;
    }

    /** Runs daily at 03:00 AM. */
    @Scheduled(cron = "0 0 3 * * *")
    @Transactional
    public void purgeExpiredResults() {
        Instant cutoff = Instant.now().minus(RETENTION_DAYS, ChronoUnit.DAYS);
        long deleted = checkResultRepository.deleteByCheckedAtBefore(cutoff);
        log.info("Purged {} check_results older than {} days (before {})",
                deleted, RETENTION_DAYS, cutoff);
    }
}
