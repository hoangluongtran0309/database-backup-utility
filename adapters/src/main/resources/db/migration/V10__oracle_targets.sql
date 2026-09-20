-- Oracle Data Pump names a database-side directory object. Its filesystem
-- path is deployment configuration, but the object name can differ per target.
ALTER TABLE database_targets
    ADD COLUMN data_pump_directory VARCHAR(128),
    ALTER COLUMN username TYPE VARCHAR(128),
    DROP CONSTRAINT ck_database_targets_engine,
    ADD CONSTRAINT ck_database_targets_engine
        CHECK (engine IN ('MYSQL', 'POSTGRESQL', 'MONGODB', 'SQLITE', 'ORACLE')),
    ADD CONSTRAINT ck_database_targets_data_pump_directory CHECK (
        (engine = 'ORACLE'
            AND data_pump_directory IS NOT NULL
            AND data_pump_directory ~ '^[A-Za-z][A-Za-z0-9_$#]{0,127}$'
            AND username ~ '^[A-Za-z][A-Za-z0-9_$#]{0,127}$')
        OR
        (engine <> 'ORACLE' AND data_pump_directory IS NULL)
    );

-- Rollback:
--   DELETE FROM database_targets WHERE engine = 'ORACLE';
--   ALTER TABLE database_targets DROP CONSTRAINT ck_database_targets_data_pump_directory;
--   ALTER TABLE database_targets DROP CONSTRAINT ck_database_targets_engine;
--   ALTER TABLE database_targets ALTER COLUMN username TYPE VARCHAR(63);
--   ALTER TABLE database_targets ADD CONSTRAINT ck_database_targets_engine
--       CHECK (engine IN ('MYSQL', 'POSTGRESQL', 'MONGODB', 'SQLITE'));
--   ALTER TABLE database_targets DROP COLUMN data_pump_directory;
