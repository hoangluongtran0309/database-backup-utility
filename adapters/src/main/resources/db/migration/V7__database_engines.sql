-- The first new backup engine. Existing targets predate this column and are
-- therefore MySQL by definition.
ALTER TABLE database_targets
    ADD COLUMN engine VARCHAR(20);

UPDATE database_targets SET engine = 'MYSQL';

ALTER TABLE database_targets
    ALTER COLUMN engine SET NOT NULL,
    ALTER COLUMN username TYPE VARCHAR(63),
    ADD CONSTRAINT ck_database_targets_engine
        CHECK (engine IN ('MYSQL', 'POSTGRESQL'));

-- Rollback:
--   ALTER TABLE database_targets DROP CONSTRAINT ck_database_targets_engine;
--   ALTER TABLE database_targets ALTER COLUMN username TYPE VARCHAR(32);
--   ALTER TABLE database_targets DROP COLUMN engine;
