package com.hoangluongtran0309.dbbackup.application.storage;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;

import org.springframework.stereotype.Service;

import com.hoangluongtran0309.dbbackup.core.exception.StorageProfileInUseException;
import com.hoangluongtran0309.dbbackup.core.model.ConnectionCheck;
import com.hoangluongtran0309.dbbackup.core.model.StorageCredentialMode;
import com.hoangluongtran0309.dbbackup.core.model.StorageProfile;
import com.hoangluongtran0309.dbbackup.core.model.StorageProvider;
import com.hoangluongtran0309.dbbackup.core.port.BackupExecutionRepository;
import com.hoangluongtran0309.dbbackup.core.port.DatabaseTargetRepository;
import com.hoangluongtran0309.dbbackup.core.port.EncryptionPort;
import com.hoangluongtran0309.dbbackup.core.port.StorageProfileRepository;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class ManageStorageProfileService {
    private final StorageProfileRepository profiles;
    private final DatabaseTargetRepository targets;
    private final BackupExecutionRepository backups;
    private final ArtifactStorageService artifacts;
    private final EncryptionPort encryption;
    private final Clock clock;

    public List<StorageProfile> listAll() { return profiles.findAll(); }
    public StorageProfile get(UUID id) { return profiles.findById(id).orElseThrow(
            () -> new NoSuchElementException("No storage profile with id " + id)); }

    public StorageProfile create(SaveStorageProfileCommand command) {
        Instant now = clock.instant();
        return profiles.save(build(UUID.randomUUID(), now, now, null, command, null));
    }

    public StorageProfile edit(UUID id, SaveStorageProfileCommand command) {
        StorageProfile current = get(id);
        if (current.getProvider() != command.provider()) {
            throw new IllegalArgumentException("Storage provider cannot be changed");
        }
        StorageProfile edited = build(id, current.getCreatedAt(), clock.instant(), null, command, current);
        if (backups.countForStorageProfile(id) > 0 && !current.sameLocation(edited)) {
            throw new IllegalStateException(
                    "Provider location settings cannot change after the profile is referenced");
        }
        return profiles.save(edited);
    }

    public ConnectionCheck test(UUID id) {
        StorageProfile profile = get(id);
        ConnectionCheck result;
        try {
            artifacts.test(profile);
            result = new ConnectionCheck(true, "Create, metadata read, content read and delete succeeded",
                    clock.instant());
        } catch (RuntimeException e) {
            result = new ConnectionCheck(false, safeMessage(e), clock.instant());
        }
        profiles.recordConnectionCheck(id, result);
        return result;
    }

    public void delete(UUID id) {
        StorageProfile profile = profiles.findById(id).orElse(null);
        if (profile == null) return;
        if (isReferenced(id)) throw new StorageProfileInUseException(profile.getName());
        profiles.deleteById(id);
    }

    private StorageProfile build(UUID id, Instant createdAt, Instant updatedAt, ConnectionCheck check,
            SaveStorageProfileCommand command, StorageProfile current) {
        StorageCredentialMode mode = command.credentialMode();
        boolean s3 = command.provider() == StorageProvider.S3;
        String accessKey = s3 && mode == StorageCredentialMode.STATIC ? command.accessKeyId() : null;
        String secretCiphertext = null;
        if (s3 && mode == StorageCredentialMode.STATIC) {
            if (command.suppliesSecret()) secretCiphertext = encryption.encrypt(command.secretAccessKey());
            else if (current != null && current.getProvider() == StorageProvider.S3
                    && current.getCredentialMode() == StorageCredentialMode.STATIC)
                secretCiphertext = current.getSecretAccessKeyCiphertext();
        }
        String serviceAccountCiphertext = null;
        if (!s3 && mode == StorageCredentialMode.SERVICE_ACCOUNT_JSON) {
            if (command.suppliesServiceAccountJson()) {
                serviceAccountCiphertext = encryption.encrypt(command.serviceAccountJson());
            } else if (current != null && current.getProvider() == StorageProvider.GCS
                    && current.getCredentialMode() == StorageCredentialMode.SERVICE_ACCOUNT_JSON) {
                serviceAccountCiphertext = current.getServiceAccountJsonCiphertext();
            }
        }
        return StorageProfile.builder().id(id).name(command.name()).provider(command.provider())
                .endpoint(command.endpoint()).region(s3 ? command.region() : null)
                .projectId(s3 ? null : command.projectId())
                .bucket(command.bucket()).keyPrefix(command.keyPrefix())
                .pathStyle(s3 && command.pathStyle()).credentialMode(mode).accessKeyId(accessKey)
                .secretAccessKeyCiphertext(secretCiphertext)
                .serviceAccountJsonCiphertext(serviceAccountCiphertext)
                .createdAt(createdAt).updatedAt(updatedAt)
                .lastConnectionCheck(check).build();
    }

    private boolean isReferenced(UUID id) {
        return targets.countForStorageProfile(id) > 0 || backups.countForStorageProfile(id) > 0;
    }

    private static String safeMessage(RuntimeException e) {
        String message = e.getMessage();
        if (message == null || message.isBlank()) return "Storage connection test failed";
        return message.length() <= 500 ? message : message.substring(0, 500);
    }
}
