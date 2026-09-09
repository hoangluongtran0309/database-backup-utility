-- A MySQL server and schema this tool is allowed to back up.
--
-- No `engine` column: MySQL is the only engine, so the column would hold the
-- same value in every row. The migration that adds a second engine is the one
-- that should introduce it.

CREATE TABLE database_targets (
    id            UUID         PRIMARY KEY,
    name          VARCHAR(100) NOT NULL,
    host          VARCHAR(255) NOT NULL,
    port          INTEGER      NOT NULL,
    database_name VARCHAR(64)  NOT NULL,
    username      VARCHAR(32)  NOT NULL,
    password_enc  TEXT         NOT NULL,
    created_at    TIMESTAMPTZ  NOT NULL,

    CONSTRAINT ck_database_targets_port CHECK (port BETWEEN 1 AND 65535)
);

-- Names are unique ignoring case and surrounding space, so "Prod" and "prod "
-- collide. This index, not a read-then-write check in the application, is what
-- decides the race between two concurrent registrations; the repository
-- adapter recognises this index by name to report the failure as a duplicate.
CREATE UNIQUE INDEX ux_database_targets_name ON database_targets (lower(btrim(name)));

-- Rollback:
--   DROP TABLE database_targets;
