package com.hoangluongtran0309.dbbackup.adapter.persistence;

import com.hoangluongtran0309.dbbackup.core.model.ConnectionCheck;
import com.hoangluongtran0309.dbbackup.core.model.StorageProfile;

final class StorageProfileMapper {
    private StorageProfileMapper() {}

    static StorageProfileEntity toEntity(StorageProfile profile) {
        StorageProfileEntity entity = new StorageProfileEntity();
        entity.setId(profile.getId());
        entity.setName(profile.getName());
        entity.setProvider(profile.getProvider());
        entity.setEndpoint(profile.getEndpoint());
        entity.setRegion(profile.getRegion());
        entity.setBucket(profile.getBucket());
        entity.setKeyPrefix(profile.getKeyPrefix());
        entity.setPathStyle(profile.isPathStyle());
        entity.setCredentialMode(profile.getCredentialMode());
        entity.setAccessKeyId(profile.getAccessKeyId());
        entity.setSecretAccessKeyEnc(profile.getSecretAccessKeyCiphertext());
        entity.setProjectId(profile.getProjectId());
        entity.setServiceAccountJsonEnc(profile.getServiceAccountJsonCiphertext());
        entity.setAccountName(profile.getAccountName());
        entity.setAccountKeyEnc(profile.getAccountKeyCiphertext());
        entity.setCreatedAt(profile.getCreatedAt());
        entity.setUpdatedAt(profile.getUpdatedAt());
        ConnectionCheck check = profile.getLastConnectionCheck();
        if (check != null) {
            entity.setLastConnectionSuccessful(check.successful());
            entity.setLastConnectionMessage(check.message());
            entity.setLastConnectionCheckedAt(check.checkedAt());
        }
        return entity;
    }

    static StorageProfile toDomain(StorageProfileEntity entity) {
        ConnectionCheck check = entity.getLastConnectionSuccessful() == null
                || entity.getLastConnectionCheckedAt() == null ? null : new ConnectionCheck(
                        entity.getLastConnectionSuccessful(), entity.getLastConnectionMessage(),
                        entity.getLastConnectionCheckedAt());
        return StorageProfile.builder()
                .id(entity.getId()).name(entity.getName()).provider(entity.getProvider())
                .endpoint(entity.getEndpoint())
                .region(entity.getRegion()).bucket(entity.getBucket()).keyPrefix(entity.getKeyPrefix())
                .pathStyle(entity.isPathStyle()).credentialMode(entity.getCredentialMode())
                .accessKeyId(entity.getAccessKeyId()).secretAccessKeyCiphertext(entity.getSecretAccessKeyEnc())
                .projectId(entity.getProjectId())
                .serviceAccountJsonCiphertext(entity.getServiceAccountJsonEnc())
                .accountName(entity.getAccountName())
                .accountKeyCiphertext(entity.getAccountKeyEnc())
                .createdAt(entity.getCreatedAt()).updatedAt(entity.getUpdatedAt())
                .lastConnectionCheck(check).build();
    }
}
