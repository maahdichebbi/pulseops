package com.pulseops.incident;

import com.pulseops.monitor.Monitor;
import com.pulseops.user.User;
import com.pulseops.user.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Optional;

/**
 * Sends incident open/recovery email alerts via Spring Mail
 * (Mailpit locally on port 1025, AWS SES in production).
 *
 * Reliability rules:
 * - Retries up to 3 times with exponential back-off (500ms, 1000ms).
 * - Never throws — SMTP failures are recorded in notification_log as FAILED
 *   so the scheduler never stalls or leaves a monitor stuck in RUNNING.
 */
@Service
public class NotificationService {

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);
    static final int MAX_ATTEMPTS = 3;
    private static final long INITIAL_BACKOFF_MS = 500L;

    @FunctionalInterface
    interface Sleeper {
        void sleep(long millis) throws InterruptedException;
    }

    private final JavaMailSender mailSender;
    private final NotificationLogRepository notificationLogRepository;
    private final UserRepository userRepository;
    private final String fromAddress;
    private final Sleeper sleeper;

    @Autowired
    public NotificationService(JavaMailSender mailSender,
                               NotificationLogRepository notificationLogRepository,
                               UserRepository userRepository,
                               @Value("${app.mail.from:alerts@pulseops.local}") String fromAddress) {
        this(mailSender, notificationLogRepository, userRepository, fromAddress, Thread::sleep);
    }

    /** Package-private constructor for unit tests (injects no-op Sleeper). */
    NotificationService(JavaMailSender mailSender,
                        NotificationLogRepository notificationLogRepository,
                        UserRepository userRepository,
                        String fromAddress,
                        Sleeper sleeper) {
        this.mailSender                = mailSender;
        this.notificationLogRepository = notificationLogRepository;
        this.userRepository            = userRepository;
        this.fromAddress               = fromAddress;
        this.sleeper                   = sleeper;
    }

    // ── Public API ────────────────────────────────────────────────────────────

    public void notifyIncidentOpened(Monitor monitor, Incident incident) {
        String subject = "[PulseOps] DOWN: " + monitor.getName();
        String body = """
                Monitor "%s" is DOWN.

                URL: %s
                Consecutive failures: %d (threshold: %d)
                Last error: %s
                Opened at: %s
                """.formatted(
                monitor.getName(),
                monitor.getUrl(),
                incident.getFailureCountAtOpen(),
                monitor.getFailureThreshold(),
                incident.getLastError() != null ? incident.getLastError() : "N/A",
                incident.getOpenedAt()
        );

        sendWithRetry(monitor.getUserId(), incident.getId(),
                NotificationLog.EventType.INCIDENT_OPENED, subject, body);
    }

    public void notifyIncidentResolved(Monitor monitor, Incident incident) {
        Duration downtime = (incident.getOpenedAt() != null && incident.getResolvedAt() != null)
                ? Duration.between(incident.getOpenedAt(), incident.getResolvedAt())
                : Duration.ZERO;

        String subject = "[PulseOps] UP: " + monitor.getName();
        String body = """
                Monitor "%s" has RECOVERED and is back UP.

                URL: %s
                Resolved at: %s
                Downtime duration: %d seconds
                """.formatted(
                monitor.getName(),
                monitor.getUrl(),
                incident.getResolvedAt(),
                downtime.getSeconds()
        );

        sendWithRetry(monitor.getUserId(), incident.getId(),
                NotificationLog.EventType.INCIDENT_RESOLVED, subject, body);
    }

    // ── Internal delivery + retry ─────────────────────────────────────────────

    private void sendWithRetry(Long userId, Long incidentId,
                               NotificationLog.EventType eventType,
                               String subject, String body) {
        Optional<User> userOpt = userRepository.findById(userId);
        if (userOpt.isEmpty()) {
            log.warn("Cannot send {} notification for incident {}: user {} not found",
                    eventType, incidentId, userId);
            return;
        }

        String recipient = userOpt.get().getEmail();
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(fromAddress);
        message.setTo(recipient);
        message.setSubject(subject);
        message.setText(body);

        String lastError = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                mailSender.send(message);
                notificationLogRepository.save(
                        NotificationLog.sent(incidentId, userId, eventType, recipient, attempt));
                log.info("Sent {} email to {} for incident {} (attempt {})",
                        eventType, recipient, incidentId, attempt);
                return;
            } catch (MailException e) {
                lastError = e.getMessage();
                log.warn("Attempt {}/{} failed sending {} email to {} for incident {}: {}",
                        attempt, MAX_ATTEMPTS, eventType, recipient, incidentId, lastError);

                if (attempt < MAX_ATTEMPTS) {
                    try {
                        sleeper.sleep(INITIAL_BACKOFF_MS * attempt);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        }

        // All retries exhausted — record failure without throwing
        notificationLogRepository.save(
                NotificationLog.failed(incidentId, userId, eventType, recipient, MAX_ATTEMPTS, lastError));
        log.error("Failed to send {} email to {} for incident {} after {} attempts",
                eventType, recipient, incidentId, MAX_ATTEMPTS);
    }
}
