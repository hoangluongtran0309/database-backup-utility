-- Where a restore's data went. Until now that was always the target the backup
-- came from, so it was never stored; a restore can now go into any registered
-- target. See ADR-014.

ALTER TABLE restore_executions
    -- RESTRICT, like every other foreign key here. Removing a target removes
    -- the records of restores into it, but the use case does that after the
    -- operator has confirmed, not the schema on any delete (ADR-008).
    ADD COLUMN target_id UUID REFERENCES database_targets (id) ON DELETE RESTRICT;

-- Every restore so far went back where its backup came from.
UPDATE restore_executions r
   SET target_id = b.target_id
  FROM backup_executions b
 WHERE b.id = r.backup_execution_id;

ALTER TABLE restore_executions
    ALTER COLUMN target_id SET NOT NULL;

CREATE INDEX idx_restore_executions_target_id ON restore_executions (target_id);

-- Rollback:
--   DROP INDEX idx_restore_executions_target_id;
--   ALTER TABLE restore_executions DROP COLUMN target_id;
-- (Rolling back loses which restores went into a target other than their
-- backup's own; those rows would then read as restores into the source.)
