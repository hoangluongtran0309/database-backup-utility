CREATE TABLE storage_profiles (
    id UUID PRIMARY KEY,
    name VARCHAR(100) NOT NULL,
    endpoint_url VARCHAR(2048),
    region VARCHAR(64) NOT NULL,
    bucket VARCHAR(255) NOT NULL,
    key_prefix VARCHAR(1024) NOT NULL DEFAULT '',
    path_style BOOLEAN NOT NULL DEFAULT FALSE,
    credential_mode VARCHAR(20) NOT NULL,
    access_key_id VARCHAR(256),
    secret_access_key_enc TEXT,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    last_connection_successful BOOLEAN,
    last_connection_message VARCHAR(500),
    last_connection_checked_at TIMESTAMPTZ,
    CONSTRAINT chk_storage_profiles_credentials CHECK (
        (credential_mode = 'STATIC' AND access_key_id IS NOT NULL AND secret_access_key_enc IS NOT NULL)
        OR (credential_mode = 'DEFAULT_CHAIN' AND access_key_id IS NULL AND secret_access_key_enc IS NULL)
    ),
    CONSTRAINT chk_storage_profiles_connection_check CHECK (
        (last_connection_successful IS NULL AND last_connection_checked_at IS NULL)
        OR (last_connection_successful IS NOT NULL AND last_connection_checked_at IS NOT NULL)
    )
);

CREATE UNIQUE INDEX uq_storage_profiles_name_ci ON storage_profiles (LOWER(name));

ALTER TABLE database_targets
    ADD COLUMN storage_profile_id UUID REFERENCES storage_profiles(id) ON DELETE RESTRICT;

ALTER TABLE backup_executions RENAME COLUMN artifact_path TO artifact_locator;
ALTER TABLE backup_executions
    ADD COLUMN storage_profile_id UUID REFERENCES storage_profiles(id) ON DELETE RESTRICT;

CREATE INDEX idx_database_targets_storage_profile_id
    ON database_targets(storage_profile_id) WHERE storage_profile_id IS NOT NULL;
CREATE INDEX idx_backup_executions_storage_profile_id
    ON backup_executions(storage_profile_id) WHERE storage_profile_id IS NOT NULL;
