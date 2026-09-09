-- The outcome of the last connection probe against a target.
--
-- Nullable as a set: a target that has never been tested has all three NULL.
-- They are written by a targeted UPDATE rather than by persisting the whole
-- row, so a probe cannot rewrite password_enc.

ALTER TABLE database_targets
    ADD COLUMN last_connection_successful BOOLEAN,
    ADD COLUMN last_connection_message    VARCHAR(500),
    ADD COLUMN last_connection_checked_at TIMESTAMPTZ;

-- Rollback:
--   ALTER TABLE database_targets
--       DROP COLUMN last_connection_successful,
--       DROP COLUMN last_connection_message,
--       DROP COLUMN last_connection_checked_at;
