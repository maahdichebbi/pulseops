package com.pulseops.incident;

import com.pulseops.monitor.CheckResult;
import com.pulseops.monitor.Monitor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * Manages incident lifecycle transitions based on HTTP check outcomes.
 *
 * Rules:
 * - A monitor opens an incident when consecutiveFailures >= failureThreshold.
 * - At most ONE OPEN incident exists per monitor (enforced by app check + DB unique index).
 * - Any single successful check resolves an OPEN incident immediately.
 */
@Service
public class IncidentService {

    private static final Logger log = LoggerFactory.getLogger(IncidentService.class);

    private final IncidentRepository incidentRepository;
    private final NotificationService notificationService;

    public IncidentService(IncidentRepository incidentRepository,
                           NotificationService notificationService) {
        this.incidentRepository  = incidentRepository;
        this.notificationService = notificationService;
    }

    /**
     * Evaluate a completed check result and open/resolve an incident if warranted.
     * Expects {@code monitor.getConsecutiveFailures()} to be already updated for this result.
     */
    @Transactional
    public void handleCheckResult(Monitor monitor, CheckResult result) {
        if (result.isSuccess()) {
            resolveIfOpen(monitor);
        } else if (monitor.getConsecutiveFailures() >= monitor.getFailureThreshold()) {
            openIfNoneExists(monitor, result);
        }
    }

    private void openIfNoneExists(Monitor monitor, CheckResult result) {
        Optional<Incident> existing = incidentRepository
                .findByMonitorIdAndStatus(monitor.getId(), Incident.Status.OPEN);
        if (existing.isPresent()) {
            // Already open — do not create a duplicate or re-send alert emails
            return;
        }

        try {
            Incident incident = Incident.open(
                    monitor.getId(),
                    monitor.getUserId(),
                    monitor.getConsecutiveFailures(),
                    result.getErrorDetail()
            );
            Incident saved = incidentRepository.save(incident);
            log.warn("Incident {} OPENED for monitor {} ({})",
                    saved.getId(), monitor.getId(), monitor.getName());

            notificationService.notifyIncidentOpened(monitor, saved);
        } catch (DataIntegrityViolationException e) {
            // Lost a rare race to uq_incidents_one_open_per_monitor — safe to ignore
            log.debug("Concurrent incident open ignored for monitor {}", monitor.getId());
        }
    }

    private void resolveIfOpen(Monitor monitor) {
        Optional<Incident> existing = incidentRepository
                .findByMonitorIdAndStatus(monitor.getId(), Incident.Status.OPEN);
        if (existing.isEmpty()) {
            return;
        }

        Incident incident = existing.get();
        incident.resolve();
        Incident saved = incidentRepository.save(incident);
        log.info("Incident {} RESOLVED for monitor {} ({})",
                saved.getId(), monitor.getId(), monitor.getName());

        notificationService.notifyIncidentResolved(monitor, saved);
    }
}
