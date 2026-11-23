package com.pulseops.monitor;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Data access layer for monitors.
 *
 * Every query is scoped to a userId — users never see each other's monitors.
 * The service layer calls findByIdAndUserId() for single-entity operations;
 * if the monitor doesn't exist OR belongs to someone else, it returns empty
 * and the service throws ResourceNotFoundException (404 not 403).
 */
public interface MonitorRepository extends JpaRepository<Monitor, Long> {

    /**
     * Paginated list of all monitors owned by a user.
     * Used by GET /api/v1/monitors.
     * idx_mon_user_id anchors this query.
     */
    Page<Monitor> findByUserId(Long userId, Pageable pageable);

    /**
     * Fetch a single monitor that belongs to userId.
     * Returns empty if the id doesn't exist OR if it belongs to another user.
     * Callers turn empty into 404 — never 403.
     */
    Optional<Monitor> findByIdAndUserId(Long id, Long userId);

    /**
     * Count monitors owned by userId.
     * Used to enforce the max-20-monitors-per-user quota before saving a new one.
     */
    long countByUserId(Long userId);

    /**
     * Poll for monitors due for a check, locking them to prevent duplicate concurrent execution.
     *
     * Combines two partial indexes:
     * 1. idx_mon_next_check: IDLE monitors whose scheduled check time has arrived.
     * 2. idx_mon_lease_expired: RUNNING monitors whose lease expired (crashed worker recovery).
     *
     * FOR UPDATE SKIP LOCKED ensures that if multiple scheduler threads/replicas query
     * simultaneously, each claims a disjoint set without blocking.
     */
    @Query(value = """
        SELECT * FROM monitors
        WHERE (
            (enabled = TRUE AND check_status = 'IDLE' AND next_check_at <= :now)
            OR
            (enabled = TRUE AND check_status = 'RUNNING' AND claimed_until <= :now)
        )
        ORDER BY next_check_at ASC
        LIMIT :limit
        FOR UPDATE SKIP LOCKED
        """, nativeQuery = true)
    List<Monitor> findClaimCandidates(@Param("now") Instant now, @Param("limit") int limit);
}
