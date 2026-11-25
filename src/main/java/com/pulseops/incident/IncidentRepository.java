package com.pulseops.incident;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface IncidentRepository extends JpaRepository<Incident, Long> {

    /**
     * Find the currently OPEN incident for a monitor (at most 1 due to partial unique index).
     * Used by IncidentService to avoid opening duplicates and to resolve on recovery.
     */
    Optional<Incident> findByMonitorIdAndStatus(Long monitorId, Incident.Status status);

    /** Paginated incidents for a single monitor (idx_inc_monitor_opened). */
    Page<Incident> findByMonitorIdOrderByOpenedAtDesc(Long monitorId, Pageable pageable);

    /** Paginated incidents across all monitors owned by a user (idx_inc_user_opened). */
    Page<Incident> findByUserIdOrderByOpenedAtDesc(Long userId, Pageable pageable);
}
