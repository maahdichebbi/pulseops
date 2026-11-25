-- V4__create_incidents.sql
-- Creates the incidents and notification_log tables for Phase 5.

CREATE TABLE incidents (
    id                    BIGSERIAL    PRIMARY KEY,
    monitor_id            BIGINT       NOT NULL REFERENCES monitors(id) ON DELETE CASCADE,
    user_id               BIGINT       NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    status                VARCHAR(16)  NOT NULL DEFAULT 'OPEN',
    opened_at             TIMESTAMPTZ  NOT NULL DEFAULT now(),
    resolved_at           TIMESTAMPTZ,
    failure_count_at_open INTEGER      NOT NULL,
    last_error            VARCHAR(500),

    CONSTRAINT chk_incidents_status CHECK (status IN ('OPEN', 'RESOLVED')),
    CONSTRAINT chk_incidents_failure_count CHECK (failure_count_at_open >= 1),

    -- Consistency invariant: OPEN incidents must have resolved_at = NULL;
    -- RESOLVED incidents must have resolved_at IS NOT NULL.
    CONSTRAINT chk_incidents_resolved_consistent CHECK (
        (status = 'OPEN'     AND resolved_at IS NULL)
        OR
        (status = 'RESOLVED' AND resolved_at IS NOT NULL)
    )
);

-- DB-level guarantee: at most ONE open incident per monitor at any time.
-- Even under concurrent scheduler ticks, Postgres rejects a duplicate OPEN row.
CREATE UNIQUE INDEX uq_incidents_one_open_per_monitor
    ON incidents(monitor_id)
    WHERE status = 'OPEN';

-- Powers GET /api/v1/monitors/{id}/incidents (paginated per-monitor history)
CREATE INDEX idx_inc_monitor_opened ON incidents(monitor_id, opened_at DESC);

-- Powers GET /api/v1/incidents (paginated all-incidents feed for a user)
CREATE INDEX idx_inc_user_opened ON incidents(user_id, opened_at DESC);


-- Audit log for every email notification attempt (open & recovery alerts).
CREATE TABLE notification_log (
    id              BIGSERIAL    PRIMARY KEY,
    incident_id     BIGINT       NOT NULL REFERENCES incidents(id) ON DELETE CASCADE,
    user_id         BIGINT       NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    event_type      VARCHAR(20)  NOT NULL,
    recipient_email VARCHAR(255) NOT NULL,
    status          VARCHAR(16)  NOT NULL,
    attempts        INTEGER      NOT NULL DEFAULT 1,
    error_message   VARCHAR(500),
    sent_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT chk_notif_event_type CHECK (event_type IN ('INCIDENT_OPENED', 'INCIDENT_RESOLVED')),
    CONSTRAINT chk_notif_status     CHECK (status IN ('SENT', 'FAILED')),
    CONSTRAINT chk_notif_attempts   CHECK (attempts >= 1)
);

CREATE INDEX idx_notif_incident ON notification_log(incident_id, sent_at DESC);
