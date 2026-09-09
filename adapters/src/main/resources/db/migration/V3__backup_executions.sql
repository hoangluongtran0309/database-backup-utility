-- One row per attempt to back up one target.
--
-- The row is written before the dump starts, so an operation that is accepted
-- always has a stable identifier to poll and a crash leaves evidence rather
-- than a lost in-memory task.

CREATE TABLE backup_executions (
    id            UUID          PRIMARY KEY,
    -- RESTRICT, not CASCADE: the backups are the valuable thing, and removing a
    -- target must not be a way to lose them, nor to strand their files on disk
    -- with nothing in the database pointing at them.
    target_id     UUID          NOT NULL REFERENCES database_targets (id) ON DELETE RESTRICT,
    status        VARCHAR(16)   NOT NULL,
    started_at    TIMESTAMPTZ   NOT NULL,
    finished_at   TIMESTAMPTZ,
    artifact_path TEXT,
    size_bytes    BIGINT,
    error_message VARCHAR(2000),

    CONSTRAINT ck_backup_executions_status
        CHECK (status IN ('RUNNING', 'SUCCEEDED', 'FAILED')),

    -- A finished execution has a finish time and a running one does not. This
    -- is the invariant the domain model enforces; stating it here too means a
    -- half-written row cannot survive a crash mid-update.
    CONSTRAINT ck_backup_executions_finished_at
        CHECK ((status = 'RUNNING') = (finished_at IS NULL)),

    CONSTRAINT ck_backup_executions_artifact
        CHECK ((status = 'SUCCEEDED') = (artifact_path IS NOT NULL AND size_bytes IS NOT NULL))
);

CREATE INDEX idx_backup_executions_started_at ON backup_executions (started_at DESC);
CREATE INDEX idx_backup_executions_target_id ON backup_executions (target_id);

-- Rollback:
--   DROP TABLE backup_executions;
