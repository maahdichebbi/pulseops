package com.pulseops.incident;

import jakarta.persistence.*;
import java.time.Instant;

/**
 * Audit record of an email notification attempt for an incident (Flyway V4).
 * Immutable after insert.
 */
@Entity
@Table(name = "notification_log")
public class NotificationLog {

    public enum EventType { INCIDENT_OPENED, INCIDENT_RESOLVED }
    public enum DeliveryStatus { SENT, FAILED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "incident_id", nullable = false, updatable = false)
    private Long incidentId;

    @Column(name = "user_id", nullable = false, updatable = false)
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false, length = 20, updatable = false)
    private EventType eventType;

    @Column(name = "recipient_email", nullable = false, length = 255, updatable = false)
    private String recipientEmail;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16, updatable = false)
    private DeliveryStatus status;

    @Column(nullable = false, updatable = false)
    private int attempts;

    @Column(name = "error_message", length = 500, updatable = false)
    private String errorMessage;

    @Column(name = "sent_at", nullable = false, updatable = false)
    private Instant sentAt;

    @PrePersist
    void onPrePersist() {
        if (sentAt == null) {
            sentAt = Instant.now();
        }
    }

    // ── Factory methods ───────────────────────────────────────────────────────

    public static NotificationLog sent(Long incidentId, Long userId, EventType eventType,
                                       String recipientEmail, int attempts) {
        NotificationLog log = new NotificationLog();
        log.incidentId     = incidentId;
        log.userId         = userId;
        log.eventType      = eventType;
        log.recipientEmail = recipientEmail;
        log.status         = DeliveryStatus.SENT;
        log.attempts       = attempts;
        return log;
    }

    public static NotificationLog failed(Long incidentId, Long userId, EventType eventType,
                                         String recipientEmail, int attempts, String errorMessage) {
        NotificationLog log = new NotificationLog();
        log.incidentId     = incidentId;
        log.userId         = userId;
        log.eventType      = eventType;
        log.recipientEmail = recipientEmail;
        log.status         = DeliveryStatus.FAILED;
        log.attempts       = attempts;
        log.errorMessage   = errorMessage != null && errorMessage.length() > 500
                ? errorMessage.substring(0, 500)
                : errorMessage;
        return log;
    }

    // ── Getters ───────────────────────────────────────────────────────────────

    public Long           getId()             { return id; }
    public Long           getIncidentId()     { return incidentId; }
    public Long           getUserId()         { return userId; }
    public EventType      getEventType()      { return eventType; }
    public String         getRecipientEmail() { return recipientEmail; }
    public DeliveryStatus getStatus()         { return status; }
    public int            getAttempts()       { return attempts; }
    public String         getErrorMessage()   { return errorMessage; }
    public Instant        getSentAt()         { return sentAt; }
}
