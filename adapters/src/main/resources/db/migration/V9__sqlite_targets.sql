-- SQLite targets name a file already mounted below SQLITE_ROOT. They have no
-- network endpoint or credentials; every other engine keeps requiring them.
ALTER TABLE database_targets
    DROP CONSTRAINT ck_database_targets_engine,
    ALTER COLUMN host DROP NOT NULL,
    ALTER COLUMN port DROP NOT NULL,
    ALTER COLUMN database_name TYPE VARCHAR(1024),
    ALTER COLUMN username DROP NOT NULL,
    ALTER COLUMN password_enc DROP NOT NULL,
    ADD CONSTRAINT ck_database_targets_engine
        CHECK (engine IN ('MYSQL', 'POSTGRESQL', 'MONGODB', 'SQLITE')),
    ADD CONSTRAINT ck_database_targets_connection_shape CHECK (
        (engine = 'SQLITE'
            AND host IS NULL
            AND port IS NULL
            AND username IS NULL
            AND password_enc IS NULL)
        OR
        (engine <> 'SQLITE'
            AND host IS NOT NULL
            AND btrim(host) <> ''
            AND port IS NOT NULL
            AND port BETWEEN 1 AND 65535
            AND username IS NOT NULL
            AND btrim(username) <> ''
            AND password_enc IS NOT NULL
            AND btrim(password_enc) <> '')
    );

-- Rollback is intentionally lossy only if SQLite rows exist: remove them
-- before restoring the old NOT NULL shape.
--   DELETE FROM database_targets WHERE engine = 'SQLITE';
--   ALTER TABLE database_targets DROP CONSTRAINT ck_database_targets_connection_shape;
--   ALTER TABLE database_targets DROP CONSTRAINT ck_database_targets_engine;
--   ALTER TABLE database_targets ALTER COLUMN host SET NOT NULL;
--   ALTER TABLE database_targets ALTER COLUMN port SET NOT NULL;
--   ALTER TABLE database_targets ALTER COLUMN database_name TYPE VARCHAR(64);
--   ALTER TABLE database_targets ALTER COLUMN username SET NOT NULL;
--   ALTER TABLE database_targets ALTER COLUMN password_enc SET NOT NULL;
--   ALTER TABLE database_targets ADD CONSTRAINT ck_database_targets_engine
--       CHECK (engine IN ('MYSQL', 'POSTGRESQL', 'MONGODB'));
