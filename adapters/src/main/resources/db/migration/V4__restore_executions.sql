-- One row per attempt to load a backup artifact back into its target.
--
-- A separate table from backup_executions, not a status on it: a backup is a
-- thing that exists, and restoring it is an event that can happen to it many
-- times, or never.

CREATE TABLE restore_executions (
    id                  UUID        PRIMARY KEY,
    -- RESTRICT for the same reason as backup_executions -> database_targets:
    -- removing a backup must not silently erase the record that it was once
    -- restored somewhere.
    backup_execution_id UUID        NOT NULL REFERENCES backup_executions (id) ON DELETE RESTRICT,
    status              VARCHAR(16) NOT NULL,
    started_at          TIMESTAMPTZ NOT NULL,
    finished_at         TIMESTAMPTZ,
    error_message       VARCHAR(2000),

    CONSTRAINT ck_restore_executions_status
        CHECK (status IN ('RUNNING', 'SUCCEEDED', 'FAILED')),

    CONSTRAINT ck_restore_executions_finished_at
        CHECK ((status = 'RUNNING') = (finished_at IS NULL))
);

CREATE INDEX idx_restore_executions_started_at ON restore_executions (started_at DESC);
CREATE INDEX idx_restore_executions_backup_execution_id
    ON restore_executions (backup_execution_id);

-- Rollback:
--   DROP TABLE restore_executions;
