package com.hoangluongtran0309.dbbackup.web.dto;

import com.hoangluongtran0309.dbbackup.application.storage.SaveStorageProfileCommand;
import com.hoangluongtran0309.dbbackup.core.model.StorageCredentialMode;
import com.hoangluongtran0309.dbbackup.core.model.StorageProfile;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class StorageProfileForm {
    @NotBlank @Size(max = 100) private String name;
    @Size(max = 2048) private String endpoint;
    @NotBlank @Size(max = 64) private String region;
    @NotBlank @Size(max = 255) private String bucket;
    @Size(max = 1024) private String keyPrefix;
    private boolean pathStyle;
    @NotNull private StorageCredentialMode credentialMode;
    @Size(max = 256) private String accessKeyId;
    private String secretAccessKey;

    public static StorageProfileForm blank() {
        StorageProfileForm form = new StorageProfileForm();
        form.setCredentialMode(StorageCredentialMode.STATIC);
        return form;
    }

    public static StorageProfileForm of(StorageProfile profile) {
        StorageProfileForm form = new StorageProfileForm();
        form.setName(profile.getName());
        form.setEndpoint(profile.getEndpoint());
        form.setRegion(profile.getRegion());
        form.setBucket(profile.getBucket());
        form.setKeyPrefix(profile.getKeyPrefix());
        form.setPathStyle(profile.isPathStyle());
        form.setCredentialMode(profile.getCredentialMode());
        form.setAccessKeyId(profile.getAccessKeyId());
        return form; // secret deliberately never leaves the server
    }

    public SaveStorageProfileCommand toCommand() {
        return new SaveStorageProfileCommand(name, endpoint, region, bucket, keyPrefix, pathStyle,
                credentialMode, accessKeyId, secretAccessKey);
    }
}
