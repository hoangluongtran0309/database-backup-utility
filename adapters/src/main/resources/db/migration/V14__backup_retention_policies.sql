-- One optional automatic-retention rule per target. Absence means disabled.
-- Outcome columns expose the latest sweep without creating an unbounded second
-- execution history solely for housekeeping.
CREATE TABLE backup_retention_policies (
    target_id          UUID        PRIMARY KEY
        REFERENCES database_targets (id) ON DELETE CASCADE,
    keep_successful    INTEGER     NOT NULL,
    created_at         TIMESTAMPTZ NOT NULL,
    updated_at         TIMESTAMPTZ NOT NULL,
    last_run_at        TIMESTAMPTZ,
    last_deleted_count INTEGER,
    last_error         VARCHAR(2000),

    CONSTRAINT ck_backup_retention_keep_positive CHECK (keep_successful >= 1),
    CONSTRAINT ck_backup_retention_timestamps CHECK (updated_at >= created_at),
    CONSTRAINT ck_backup_retention_result CHECK (
        (last_run_at IS NULL AND last_deleted_count IS NULL AND last_error IS NULL)
        OR
        (last_run_at IS NOT NULL AND last_deleted_count IS NOT NULL AND last_deleted_count >= 0)
    )
);

-- Supports newest-first selection within one target without indexing failed
-- and running attempts, which retention never touches.
CREATE INDEX idx_backup_executions_retention
    ON backup_executions (target_id, started_at DESC, id DESC)
    WHERE status = 'SUCCEEDED';

-- Rollback:
--   DROP INDEX idx_backup_executions_retention;
--   DROP TABLE backup_retention_policies;
