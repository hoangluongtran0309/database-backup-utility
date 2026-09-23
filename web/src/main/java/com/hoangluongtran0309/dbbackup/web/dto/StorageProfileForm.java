package com.hoangluongtran0309.dbbackup.web.dto;

import com.hoangluongtran0309.dbbackup.application.storage.SaveStorageProfileCommand;
import com.hoangluongtran0309.dbbackup.core.model.StorageCredentialMode;
import com.hoangluongtran0309.dbbackup.core.model.StorageProfile;
import com.hoangluongtran0309.dbbackup.core.model.StorageProvider;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class StorageProfileForm {
    @NotBlank @Size(max = 100) private String name;
    @NotNull private StorageProvider provider;
    @Size(max = 2048) private String endpoint;
    @Size(max = 64) private String region;
    @Size(max = 255) private String projectId;
    @Size(max = 24) private String accountName;
    @NotBlank @Size(max = 255) private String bucket;
    @Size(max = 1024) private String keyPrefix;
    private boolean pathStyle;
    @NotNull private StorageCredentialMode credentialMode;
    @Size(max = 256) private String accessKeyId;
    private String secretAccessKey;
    @Size(max = 65536) private String serviceAccountJson;
    private String accountKey;

    public static StorageProfileForm blank() {
        return blank(StorageProvider.S3);
    }

    public static StorageProfileForm blank(StorageProvider provider) {
        StorageProfileForm form = new StorageProfileForm();
        form.setProvider(provider);
        form.setCredentialMode(switch (provider) {
            case S3 -> StorageCredentialMode.STATIC;
            case GCS -> StorageCredentialMode.APPLICATION_DEFAULT;
            case AZURE_BLOB -> StorageCredentialMode.AZURE_DEFAULT;
        });
        return form;
    }

    public static StorageProfileForm of(StorageProfile profile) {
        StorageProfileForm form = new StorageProfileForm();
        form.setName(profile.getName());
        form.setProvider(profile.getProvider());
        form.setEndpoint(profile.getEndpoint());
        form.setRegion(profile.getRegion());
        form.setProjectId(profile.getProjectId());
        form.setAccountName(profile.getAccountName());
        form.setBucket(profile.getBucket());
        form.setKeyPrefix(profile.getKeyPrefix());
        form.setPathStyle(profile.isPathStyle());
        form.setCredentialMode(profile.getCredentialMode());
        form.setAccessKeyId(profile.getAccessKeyId());
        return form; // secret deliberately never leaves the server
    }

    public SaveStorageProfileCommand toCommand() {
        return new SaveStorageProfileCommand(name, provider, endpoint, region, projectId, accountName,
                bucket, keyPrefix, pathStyle, credentialMode, accessKeyId, secretAccessKey,
                serviceAccountJson, accountKey);
    }
}
