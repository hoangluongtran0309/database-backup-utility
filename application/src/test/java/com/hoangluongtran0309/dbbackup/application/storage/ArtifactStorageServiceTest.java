package com.hoangluongtran0309.dbbackup.application.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.junit.jupiter.api.extension.ExtendWith;

import com.hoangluongtran0309.dbbackup.core.model.ArtifactReference;
import com.hoangluongtran0309.dbbackup.core.model.StorageCredentialMode;
import com.hoangluongtran0309.dbbackup.core.model.StorageProfile;
import com.hoangluongtran0309.dbbackup.core.port.EncryptionPort;
import com.hoangluongtran0309.dbbackup.core.port.S3StoragePort;
import com.hoangluongtran0309.dbbackup.core.port.StagingStoragePort;
import com.hoangluongtran0309.dbbackup.core.port.StoragePort;
import com.hoangluongtran0309.dbbackup.core.port.StorageProfileRepository;

@ExtendWith(MockitoExtension.class)
class ArtifactStorageServiceTest {
    @Mock StoragePort local;
    @Mock StagingStoragePort staging;
    @Mock S3StoragePort s3;
    @Mock StorageProfileRepository profiles;
    @Mock EncryptionPort encryption;
    ArtifactStorageService service;

    @BeforeEach void setUp() { service = new ArtifactStorageService(local, staging, s3, profiles, encryption); }

    @Test
    void localWritesKeepTheAbsolutePathAsLocator() {
        Path path = Path.of("/backups/shop.sql.gz");
        when(local.locationFor("shop.sql.gz")).thenReturn(path);
        var prepared = service.prepareWrite(null, UUID.randomUUID(), UUID.randomUUID(), "shop.sql.gz");
        assertThat(prepared.path()).isEqualTo(path);
        assertThat(service.publish(prepared)).isEqualTo(new ArtifactReference(null, path.toString()));
    }

    @Test
    void s3WritesUseCollisionFreeKeyAndDeleteStagingOnClose() {
        UUID profileId = UUID.randomUUID();
        UUID targetId = UUID.randomUUID();
        UUID executionId = UUID.randomUUID();
        Path staged = Path.of("/staging", executionId.toString(), "shop.sql.gz");
        StorageProfile profile = profile(profileId);
        when(profiles.findById(profileId)).thenReturn(Optional.of(profile));
        when(staging.locationFor(executionId, "shop.sql.gz")).thenReturn(staged);
        when(encryption.decrypt("encrypted-secret")).thenReturn("plain-secret");

        try (var prepared = service.prepareWrite(profileId, targetId, executionId, "shop.sql.gz")) {
            ArtifactReference reference = service.publish(prepared);
            assertThat(reference.locator()).isEqualTo("daily/" + targetId + "/" + executionId + "/shop.sql.gz");
            verify(s3).upload(org.mockito.ArgumentMatchers.any(),
                    org.mockito.ArgumentMatchers.eq(reference.locator()),
                    org.mockito.ArgumentMatchers.eq(staged));
        }
        verify(staging).deleteOperation(executionId);
    }

    @Test
    void failedDownloadStillDeletesItsOperationDirectory() {
        UUID profileId = UUID.randomUUID();
        UUID operationId = UUID.randomUUID();
        Path staged = Path.of("/staging/file.sql.gz");
        when(profiles.findById(profileId)).thenReturn(Optional.of(profile(profileId)));
        when(staging.locationFor(operationId, "file.sql.gz")).thenReturn(staged);
        when(encryption.decrypt("encrypted-secret")).thenReturn("plain-secret");
        org.mockito.Mockito.doThrow(new IllegalStateException("network"))
                .when(s3).download(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any());

        assertThatThrownBy(() -> service.materialize(
                new ArtifactReference(profileId, "daily/t/e/file.sql.gz"), operationId, "file.sql.gz"))
                .hasMessage("network");
        verify(staging).deleteOperation(operationId);
    }

    private static StorageProfile profile(UUID id) {
        Instant now = Instant.parse("2026-09-22T08:00:00Z");
        return StorageProfile.builder().id(id).name("archive").endpoint("http://s3.test:9090")
                .region("us-east-1").bucket("backups").keyPrefix("daily").pathStyle(true)
                .credentialMode(StorageCredentialMode.STATIC).accessKeyId("access")
                .secretAccessKeyCiphertext("encrypted-secret").createdAt(now).updatedAt(now).build();
    }
}
