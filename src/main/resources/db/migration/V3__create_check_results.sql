-- V3__create_check_results.sql
-- Stores individual HTTP check outcomes for each monitor.

CREATE TABLE check_results (
    id           BIGSERIAL    PRIMARY KEY,
    monitor_id   BIGINT       NOT NULL REFERENCES monitors(id) ON DELETE CASCADE,
    success      BOOLEAN      NOT NULL,
    status_code  INTEGER,                       -- NULL for network/timeout errors (no HTTP response)
    latency_ms   INTEGER,                       -- NULL for network errors
    error_detail VARCHAR(500),                  -- human-readable failure reason
    checked_at   TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- Supports paginated history and per-monitor uptime aggregation queries.
-- Covers: SELECT * FROM check_results WHERE monitor_id=? ORDER BY checked_at DESC
CREATE INDEX idx_cr_monitor_checked ON check_results(monitor_id, checked_at DESC);

-- Supports the daily purge job: DELETE FROM check_results WHERE checked_at < now() - interval '30 days'
-- Without this index the purge would do a full table scan.
CREATE INDEX idx_cr_checked_at ON check_results(checked_at);
