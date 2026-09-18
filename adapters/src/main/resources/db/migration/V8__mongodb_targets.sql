-- MongoDB users commonly live in `admin`, independently of the database being
-- backed up. Existing SQL targets have no authentication database at all.
ALTER TABLE database_targets
    ADD COLUMN authentication_database VARCHAR(64);

ALTER TABLE database_targets
    DROP CONSTRAINT ck_database_targets_engine,
    ADD CONSTRAINT ck_database_targets_engine
        CHECK (engine IN ('MYSQL', 'POSTGRESQL', 'MONGODB')),
    ADD CONSTRAINT ck_database_targets_authentication_database CHECK (
        (engine = 'MONGODB'
            AND authentication_database IS NOT NULL
            AND btrim(authentication_database) <> '')
        OR
        (engine <> 'MONGODB' AND authentication_database IS NULL)
    );

-- Rollback:
--   ALTER TABLE database_targets DROP CONSTRAINT ck_database_targets_authentication_database;
--   ALTER TABLE database_targets DROP CONSTRAINT ck_database_targets_engine;
--   ALTER TABLE database_targets ADD CONSTRAINT ck_database_targets_engine
--       CHECK (engine IN ('MYSQL', 'POSTGRESQL'));
--   ALTER TABLE database_targets DROP COLUMN authentication_database;
