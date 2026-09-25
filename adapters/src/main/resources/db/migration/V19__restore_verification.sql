ALTER TABLE database_targets
    ADD COLUMN verify_after_backup BOOLEAN NOT NULL DEFAULT FALSE;

CREATE TABLE restore_verification_executions (
    id                  UUID         PRIMARY KEY,
    backup_execution_id UUID         NOT NULL REFERENCES backup_executions (id) ON DELETE CASCADE,
    status              VARCHAR(16)  NOT NULL,
    started_at          TIMESTAMPTZ  NOT NULL,
    finished_at         TIMESTAMPTZ,
    checked_objects     INTEGER,
    result_summary      VARCHAR(2000),
    error_message       VARCHAR(2000),
    CONSTRAINT ck_restore_verification_status
        CHECK (status IN ('RUNNING', 'SUCCEEDED', 'FAILED')),
    CONSTRAINT ck_restore_verification_finished
        CHECK ((status = 'RUNNING' AND finished_at IS NULL)
            OR (status <> 'RUNNING' AND finished_at IS NOT NULL)),
    CONSTRAINT ck_restore_verification_result
        CHECK ((status = 'SUCCEEDED' AND checked_objects >= 0 AND result_summary IS NOT NULL AND error_message IS NULL)
            OR (status = 'FAILED' AND checked_objects IS NULL AND result_summary IS NULL AND error_message IS NOT NULL)
            OR (status = 'RUNNING' AND checked_objects IS NULL AND result_summary IS NULL AND error_message IS NULL))
);

CREATE INDEX idx_restore_verification_backup_history
    ON restore_verification_executions (backup_execution_id, started_at DESC, id DESC);

CREATE UNIQUE INDEX uq_restore_verification_one_running
    ON restore_verification_executions (backup_execution_id)
    WHERE status = 'RUNNING';

-- Rollback:
-- DROP TABLE restore_verification_executions;
-- ALTER TABLE database_targets DROP COLUMN verify_after_backup;
