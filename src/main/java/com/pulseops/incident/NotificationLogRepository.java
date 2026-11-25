package com.pulseops.incident;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface NotificationLogRepository extends JpaRepository<NotificationLog, Long> {

    /** Fetch delivery attempts for an incident, newest first. */
    List<NotificationLog> findByIncidentIdOrderBySentAtDesc(Long incidentId);
}
