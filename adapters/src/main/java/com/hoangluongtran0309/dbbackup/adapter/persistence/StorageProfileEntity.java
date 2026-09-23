package com.hoangluongtran0309.dbbackup.adapter.persistence;

import java.time.Instant;
import java.util.UUID;

import com.hoangluongtran0309.dbbackup.core.model.StorageCredentialMode;
import com.hoangluongtran0309.dbbackup.core.model.StorageProvider;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "storage_profiles")
@Getter
@Setter
@NoArgsConstructor
class StorageProfileEntity {
    @Id private UUID id;
    @Column(nullable = false, length = 100) private String name;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private StorageProvider provider;
    @Column(name = "endpoint_url", length = 2048) private String endpoint;
    @Column(length = 64) private String region;
    @Column(nullable = false, length = 255) private String bucket;
    @Column(name = "key_prefix", nullable = false, length = 1024) private String keyPrefix;
    @Column(name = "path_style", nullable = false) private boolean pathStyle;
    @Enumerated(EnumType.STRING)
    @Column(name = "credential_mode", nullable = false, length = 20)
    private StorageCredentialMode credentialMode;
    @Column(name = "access_key_id", length = 256) private String accessKeyId;
    @Column(name = "secret_access_key_enc", columnDefinition = "text") private String secretAccessKeyEnc;
    @Column(name = "project_id", length = 255) private String projectId;
    @Column(name = "service_account_json_enc", columnDefinition = "text")
    private String serviceAccountJsonEnc;
    @Column(name = "created_at", nullable = false) private Instant createdAt;
    @Column(name = "updated_at", nullable = false) private Instant updatedAt;
    @Column(name = "last_connection_successful") private Boolean lastConnectionSuccessful;
    @Column(name = "last_connection_message", length = 500) private String lastConnectionMessage;
    @Column(name = "last_connection_checked_at") private Instant lastConnectionCheckedAt;
}
