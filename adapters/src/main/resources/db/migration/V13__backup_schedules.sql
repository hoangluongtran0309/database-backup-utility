-- Operator-managed recurring logical backups. The cron text uses Quartz syntax
-- and the zone is an IANA/ZoneId identifier interpreted by the application.
-- Quartz's in-memory triggers are derived from these rows at every startup;
-- this table is the durable source of truth.
CREATE TABLE backup_schedules (
    id              UUID         PRIMARY KEY,
    target_id       UUID         NOT NULL REFERENCES database_targets (id) ON DELETE RESTRICT,
    name            VARCHAR(100) NOT NULL,
    cron_expression VARCHAR(120) NOT NULL,
    zone_id         VARCHAR(64)  NOT NULL,
    enabled         BOOLEAN      NOT NULL,
    created_at      TIMESTAMPTZ  NOT NULL,
    updated_at      TIMESTAMPTZ  NOT NULL,

    CONSTRAINT ck_backup_schedules_name_not_blank CHECK (btrim(name) <> ''),
    CONSTRAINT ck_backup_schedules_cron_not_blank CHECK (btrim(cron_expression) <> ''),
    CONSTRAINT ck_backup_schedules_zone_not_blank CHECK (btrim(zone_id) <> ''),
    CONSTRAINT ck_backup_schedules_timestamps CHECK (updated_at >= created_at)
);

CREATE UNIQUE INDEX ux_backup_schedules_name
    ON backup_schedules (lower(btrim(name)));
CREATE INDEX idx_backup_schedules_target_id ON backup_schedules (target_id);

-- Rollback:
--   DROP TABLE backup_schedules;
