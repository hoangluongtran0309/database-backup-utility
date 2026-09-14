-- The SHA-256 of each artifact as stored, recorded when the backup succeeds and
-- checked before it is restored. See ADR-013.
--
-- Nullable, and not backfilled: backups made before this migration have no
-- checksum, and computing one now would only certify whatever is on disk
-- today — including a file that has already been damaged.

ALTER TABLE backup_executions
    ADD COLUMN sha256 VARCHAR(64);

-- Only a successful backup has an artifact to checksum, and the value is in the
-- form sha256sum prints, so an operator can compare the two by eye. The
-- pattern also pins the length, which is why the column is not CHAR(64).
ALTER TABLE backup_executions
    ADD CONSTRAINT ck_backup_executions_sha256
        CHECK (sha256 IS NULL OR (status = 'SUCCEEDED' AND sha256 ~ '^[0-9a-f]{64}$'));

-- Rollback:
--   ALTER TABLE backup_executions
--       DROP CONSTRAINT ck_backup_executions_sha256,
--       DROP COLUMN sha256;
