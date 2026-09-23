ALTER TABLE storage_profiles ADD COLUMN account_name VARCHAR(24);
ALTER TABLE storage_profiles ADD COLUMN account_key_enc TEXT;

ALTER TABLE storage_profiles DROP CONSTRAINT chk_storage_profiles_provider;
ALTER TABLE storage_profiles DROP CONSTRAINT chk_storage_profiles_configuration;

ALTER TABLE storage_profiles ADD CONSTRAINT chk_storage_profiles_provider
    CHECK (provider IN ('S3', 'GCS', 'AZURE_BLOB'));

ALTER TABLE storage_profiles ADD CONSTRAINT chk_storage_profiles_configuration CHECK (
    (
        provider = 'S3'
        AND region IS NOT NULL
        AND project_id IS NULL
        AND service_account_json_enc IS NULL
        AND account_name IS NULL
        AND account_key_enc IS NULL
        AND (
            (credential_mode = 'STATIC' AND access_key_id IS NOT NULL AND secret_access_key_enc IS NOT NULL)
            OR (credential_mode = 'DEFAULT_CHAIN' AND access_key_id IS NULL AND secret_access_key_enc IS NULL)
        )
    )
    OR
    (
        provider = 'GCS'
        AND region IS NULL
        AND path_style = FALSE
        AND access_key_id IS NULL
        AND secret_access_key_enc IS NULL
        AND project_id IS NOT NULL
        AND account_name IS NULL
        AND account_key_enc IS NULL
        AND (
            (credential_mode = 'SERVICE_ACCOUNT_JSON' AND service_account_json_enc IS NOT NULL)
            OR (credential_mode = 'APPLICATION_DEFAULT' AND service_account_json_enc IS NULL)
        )
    )
    OR
    (
        provider = 'AZURE_BLOB'
        AND region IS NULL
        AND path_style = FALSE
        AND access_key_id IS NULL
        AND secret_access_key_enc IS NULL
        AND project_id IS NULL
        AND service_account_json_enc IS NULL
        AND account_name IS NOT NULL
        AND account_name ~ '^[a-z0-9]{3,24}$'
        AND (
            (credential_mode = 'ACCOUNT_KEY' AND account_key_enc IS NOT NULL)
            OR (credential_mode = 'AZURE_DEFAULT' AND account_key_enc IS NULL)
        )
    )
);
