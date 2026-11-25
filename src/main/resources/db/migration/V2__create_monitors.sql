-- V2__create_monitors.sql
-- Creates the monitors table and its performance indexes.

CREATE TABLE monitors (
    id                   BIGSERIAL     PRIMARY KEY,
    user_id              BIGINT        NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    name                 VARCHAR(100)  NOT NULL,
    url                  VARCHAR(2048) NOT NULL,
    interval_minutes     INTEGER       NOT NULL DEFAULT 5,
    timeout_seconds      INTEGER       NOT NULL DEFAULT 10,
    failure_threshold    INTEGER       NOT NULL DEFAULT 3,
    consecutive_failures INTEGER       NOT NULL DEFAULT 0,
    check_status         VARCHAR(10)   NOT NULL DEFAULT 'IDLE',
    claimed_until        TIMESTAMPTZ,
    claim_token          UUID,
    enabled              BOOLEAN       NOT NULL DEFAULT TRUE,
    next_check_at        TIMESTAMPTZ   NOT NULL DEFAULT now(),
    last_status          VARCHAR(10),
    last_checked_at      TIMESTAMPTZ,
    last_response_ms     INTEGER,
    created_at           TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at           TIMESTAMPTZ   NOT NULL DEFAULT now(),

    CONSTRAINT chk_monitors_interval_minutes CHECK (interval_minutes BETWEEN 1 AND 60),
    CONSTRAINT chk_monitors_timeout_seconds  CHECK (timeout_seconds BETWEEN 1 AND 30),
    CONSTRAINT chk_monitors_threshold        CHECK (failure_threshold BETWEEN 1 AND 10),
    CONSTRAINT chk_monitors_consecutive      CHECK (consecutive_failures >= 0),
    CONSTRAINT chk_monitors_check_status     CHECK (check_status IN ('IDLE', 'RUNNING')),
    CONSTRAINT chk_monitors_last_status      CHECK (last_status IS NULL OR last_status IN ('UP', 'DOWN')),

    -- Consistency invariant: a RUNNING monitor must have a lease (both columns together or neither).
    -- Prevents "ghost locks" where check_status = RUNNING but claim fields are NULL,
    -- which would cause the scheduler to treat the row as permanently locked.
    -- Logical form: NOT RUNNING  OR  (claim_token IS NOT NULL AND claimed_until IS NOT NULL)
    CONSTRAINT chk_monitors_claim_consistent CHECK (
        check_status <> 'RUNNING'
        OR (claim_token IS NOT NULL AND claimed_until IS NOT NULL)
    )
);

-- Index for listing monitors by user owner
CREATE INDEX idx_mon_user_id ON monitors(user_id);

-- Partial index for scheduler tick: finds monitors due for a new check.
-- Only covers IDLE + enabled rows — physically small, cheap to scan.
CREATE INDEX idx_mon_next_check ON monitors(next_check_at)
    WHERE enabled = TRUE AND check_status = 'IDLE';

-- Partial index for lease recovery: finds monitors held by a worker that
-- crashed without releasing its lock (claimed_until has passed but check_status
-- is still RUNNING). The scheduler uses this to reclaim abandoned leases.
CREATE INDEX idx_mon_lease_expired ON monitors(claimed_until)
    WHERE enabled = TRUE AND check_status = 'RUNNING';
