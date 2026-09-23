package com.hoangluongtran0309.dbbackup.application.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
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
import com.hoangluongtran0309.dbbackup.core.model.StorageProvider;
import com.hoangluongtran0309.dbbackup.core.port.EncryptionPort;
import com.hoangluongtran0309.dbbackup.core.port.AzureBlobStoragePort;
import com.hoangluongtran0309.dbbackup.core.port.GcsStoragePort;
import com.hoangluongtran0309.dbbackup.core.port.S3StoragePort;
import com.hoangluongtran0309.dbbackup.core.port.StagingStoragePort;
import com.hoangluongtran0309.dbbackup.core.port.StoragePort;
import com.hoangluongtran0309.dbbackup.core.port.StorageProfileRepository;

@ExtendWith(MockitoExtension.class)
class ArtifactStorageServiceTest {
    @Mock StoragePort local;
    @Mock StagingStoragePort staging;
    @Mock S3StoragePort s3;
    @Mock GcsStoragePort gcs;
    @Mock AzureBlobStoragePort azure;
    @Mock StorageProfileRepository profiles;
    @Mock EncryptionPort encryption;
    ArtifactStorageService service;

    @BeforeEach void setUp() {
        service = new ArtifactStorageService(local, staging, s3, gcs, azure, profiles, encryption);
    }

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

    @Test
    void gcsWritesUseTheSameCollisionFreeObjectKey() {
        UUID profileId = UUID.randomUUID();
        UUID targetId = UUID.randomUUID();
        UUID executionId = UUID.randomUUID();
        Path staged = Path.of("/staging", executionId.toString(), "shop.dump");
        StorageProfile profile = StorageProfile.builder().id(profileId).name("gcs")
                .provider(StorageProvider.GCS).projectId("project").bucket("backups").keyPrefix("daily")
                .credentialMode(StorageCredentialMode.APPLICATION_DEFAULT)
                .createdAt(Instant.EPOCH).updatedAt(Instant.EPOCH).build();
        when(profiles.findById(profileId)).thenReturn(Optional.of(profile));
        when(staging.locationFor(executionId, "shop.dump")).thenReturn(staged);

        try (var prepared = service.prepareWrite(profileId, targetId, executionId, "shop.dump")) {
            ArtifactReference reference = service.publish(prepared);
            verify(gcs).upload(org.mockito.ArgumentMatchers.any(),
                    org.mockito.ArgumentMatchers.eq("daily/" + targetId + "/" + executionId + "/shop.dump"),
                    org.mockito.ArgumentMatchers.eq(staged));
        }
    }

    @Test
    void gcsReadMaterializeTestAndDeleteStayOnGcs() throws Exception {
        UUID profileId = UUID.randomUUID();
        UUID operationId = UUID.randomUUID();
        StorageProfile profile = gcsProfile(profileId, StorageCredentialMode.SERVICE_ACCOUNT_JSON,
                "sealed-json");
        ArtifactReference reference = new ArtifactReference(profileId, "daily/t/e/shop.dump");
        Path staged = Path.of("/staging", operationId.toString(), "shop.dump");
        when(profiles.findById(profileId)).thenReturn(Optional.of(profile));
        when(encryption.decrypt("sealed-json")).thenReturn("plain-json");
        when(gcs.exists(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.eq(reference.locator()))).thenReturn(true);
        when(gcs.openForReading(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.eq(reference.locator())))
                .thenReturn(new ByteArrayInputStream("artifact".getBytes()));
        when(staging.locationFor(operationId, "shop.dump")).thenReturn(staged);

        assertThat(service.exists(reference)).isTrue();
        try (var input = service.openForReading(reference)) {
            assertThat(input.readAllBytes()).isEqualTo("artifact".getBytes());
        }
        try (var prepared = service.materialize(reference, operationId, "shop.dump")) {
            assertThat(prepared.path()).isEqualTo(staged);
        }
        service.test(profile);
        service.delete(reference);

        var connection = org.mockito.ArgumentCaptor.forClass(
                com.hoangluongtran0309.dbbackup.core.model.GcsStorageConnection.class);
        verify(gcs).test(connection.capture());
        assertThat(connection.getValue().serviceAccountJson()).isEqualTo("plain-json");
        verify(gcs).download(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.eq(reference.locator()),
                org.mockito.ArgumentMatchers.eq(staged));
        verify(gcs).delete(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.eq(reference.locator()));
        verify(staging).deleteOperation(operationId);
    }

    @Test
    void azureOperationsStayOnAzureAndDecryptTheAccountKey() throws Exception {
        UUID profileId = UUID.randomUUID();
        UUID targetId = UUID.randomUUID();
        UUID operationId = UUID.randomUUID();
        Path staged = Path.of("/staging", operationId.toString(), "shop.dump");
        StorageProfile profile = azureProfile(profileId, StorageCredentialMode.ACCOUNT_KEY, "sealed-key");
        when(profiles.findById(profileId)).thenReturn(Optional.of(profile));
        when(encryption.decrypt("sealed-key")).thenReturn("plain-key");
        when(staging.locationFor(operationId, "shop.dump")).thenReturn(staged);

        ArtifactReference reference;
        try (var prepared = service.prepareWrite(profileId, targetId, operationId, "shop.dump")) {
            reference = service.publish(prepared);
        }
        when(azure.exists(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.eq(reference.locator()))).thenReturn(true);
        when(azure.openForReading(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.eq(reference.locator())))
                .thenReturn(new ByteArrayInputStream("artifact".getBytes()));

        assertThat(service.exists(reference)).isTrue();
        try (var input = service.openForReading(reference)) {
            assertThat(input.readAllBytes()).isEqualTo("artifact".getBytes());
        }
        try (var prepared = service.materialize(reference, operationId, "shop.dump")) {
            assertThat(prepared.path()).isEqualTo(staged);
        }
        service.test(profile);
        service.delete(reference);

        var connection = org.mockito.ArgumentCaptor.forClass(
                com.hoangluongtran0309.dbbackup.core.model.AzureBlobStorageConnection.class);
        verify(azure).test(connection.capture());
        assertThat(connection.getValue().accountKey()).isEqualTo("plain-key");
        verify(azure).upload(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.eq("daily/" + targetId + "/" + operationId + "/shop.dump"),
                org.mockito.ArgumentMatchers.eq(staged));
        verify(azure).download(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.eq(reference.locator()),
                org.mockito.ArgumentMatchers.eq(staged));
        verify(azure).delete(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.eq(reference.locator()));
        verify(staging, org.mockito.Mockito.times(2)).deleteOperation(operationId);
    }

    @Test
    void failedAzureDownloadDeletesItsOperationDirectory() {
        UUID profileId = UUID.randomUUID();
        UUID operationId = UUID.randomUUID();
        Path staged = Path.of("/staging", operationId.toString(), "shop.dump");
        when(profiles.findById(profileId)).thenReturn(Optional.of(
                azureProfile(profileId, StorageCredentialMode.AZURE_DEFAULT, null)));
        when(staging.locationFor(operationId, "shop.dump")).thenReturn(staged);
        org.mockito.Mockito.doThrow(new IllegalStateException("azure unavailable"))
                .when(azure).download(org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());

        assertThatThrownBy(() -> service.materialize(
                new ArtifactReference(profileId, "daily/t/e/shop.dump"), operationId, "shop.dump"))
                .hasMessage("azure unavailable");

        verify(staging).deleteOperation(operationId);
        verify(encryption, org.mockito.Mockito.never()).decrypt(org.mockito.ArgumentMatchers.any());
    }

    private static StorageProfile gcsProfile(
            UUID id, StorageCredentialMode mode, String encryptedJson) {
        return StorageProfile.builder().id(id).name("gcs").provider(StorageProvider.GCS)
                .projectId("project").bucket("backups").keyPrefix("daily")
                .credentialMode(mode).serviceAccountJsonCiphertext(encryptedJson)
                .createdAt(Instant.EPOCH).updatedAt(Instant.EPOCH).build();
    }

    private static StorageProfile profile(UUID id) {
        Instant now = Instant.parse("2026-09-22T08:00:00Z");
        return StorageProfile.builder().id(id).name("archive").provider(StorageProvider.S3)
                .endpoint("http://s3.test:9090")
                .region("us-east-1").bucket("backups").keyPrefix("daily").pathStyle(true)
                .credentialMode(StorageCredentialMode.STATIC).accessKeyId("access")
                .secretAccessKeyCiphertext("encrypted-secret").createdAt(now).updatedAt(now).build();
    }

    private static StorageProfile azureProfile(
            UUID id, StorageCredentialMode mode, String encryptedKey) {
        return StorageProfile.builder().id(id).name("azure").provider(StorageProvider.AZURE_BLOB)
                .accountName("backupaccount").bucket("backups").keyPrefix("daily")
                .credentialMode(mode).accountKeyCiphertext(encryptedKey)
                .createdAt(Instant.EPOCH).updatedAt(Instant.EPOCH).build();
    }
}
