-- MariaDB uses the existing network target shape. Its own client adapters and
-- artifact format are selected by the engine value; no new target fields are
-- needed.
ALTER TABLE database_targets
    DROP CONSTRAINT ck_database_targets_engine,
    ADD CONSTRAINT ck_database_targets_engine
        CHECK (engine IN ('MYSQL', 'POSTGRESQL', 'MONGODB', 'SQLITE', 'ORACLE', 'MARIADB'));

-- Rollback:
--   DELETE FROM database_targets WHERE engine = 'MARIADB';
--   ALTER TABLE database_targets DROP CONSTRAINT ck_database_targets_engine;
--   ALTER TABLE database_targets ADD CONSTRAINT ck_database_targets_engine
--       CHECK (engine IN ('MYSQL', 'POSTGRESQL', 'MONGODB', 'SQLITE', 'ORACLE'));
