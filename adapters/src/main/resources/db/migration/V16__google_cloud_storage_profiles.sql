ALTER TABLE storage_profiles ADD COLUMN provider VARCHAR(20);
UPDATE storage_profiles SET provider = 'S3';
ALTER TABLE storage_profiles ALTER COLUMN provider SET NOT NULL;

ALTER TABLE storage_profiles ADD COLUMN project_id VARCHAR(255);
ALTER TABLE storage_profiles ADD COLUMN service_account_json_enc TEXT;
ALTER TABLE storage_profiles ALTER COLUMN region DROP NOT NULL;

ALTER TABLE storage_profiles DROP CONSTRAINT chk_storage_profiles_credentials;
ALTER TABLE storage_profiles ADD CONSTRAINT chk_storage_profiles_provider
    CHECK (provider IN ('S3', 'GCS'));
ALTER TABLE storage_profiles ADD CONSTRAINT chk_storage_profiles_configuration CHECK (
    (
        provider = 'S3'
        AND region IS NOT NULL
        AND project_id IS NULL
        AND service_account_json_enc IS NULL
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
        AND (
            (credential_mode = 'SERVICE_ACCOUNT_JSON' AND service_account_json_enc IS NOT NULL)
            OR (credential_mode = 'APPLICATION_DEFAULT' AND service_account_json_enc IS NULL)
        )
    )
);
