-- SQL Server uses the existing network target shape. SqlPackage and sqlcmd
-- are selected by the engine value; no target-specific columns are needed.
ALTER TABLE database_targets
    DROP CONSTRAINT ck_database_targets_engine,
    ADD CONSTRAINT ck_database_targets_engine
        CHECK (engine IN ('MYSQL', 'POSTGRESQL', 'MONGODB', 'SQLITE', 'ORACLE', 'MARIADB', 'SQLSERVER'));

-- Rollback:
--   DELETE FROM database_targets WHERE engine = 'SQLSERVER';
--   ALTER TABLE database_targets DROP CONSTRAINT ck_database_targets_engine;
--   ALTER TABLE database_targets ADD CONSTRAINT ck_database_targets_engine
--       CHECK (engine IN ('MYSQL', 'POSTGRESQL', 'MONGODB', 'SQLITE', 'ORACLE', 'MARIADB'));
