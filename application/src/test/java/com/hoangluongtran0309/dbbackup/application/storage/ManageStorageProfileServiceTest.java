package com.hoangluongtran0309.dbbackup.application.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.hoangluongtran0309.dbbackup.core.exception.StorageProfileInUseException;
import com.hoangluongtran0309.dbbackup.core.model.StorageCredentialMode;
import com.hoangluongtran0309.dbbackup.core.model.StorageProfile;
import com.hoangluongtran0309.dbbackup.core.model.StorageProvider;
import com.hoangluongtran0309.dbbackup.core.port.BackupExecutionRepository;
import com.hoangluongtran0309.dbbackup.core.port.DatabaseTargetRepository;
import com.hoangluongtran0309.dbbackup.core.port.EncryptionPort;
import com.hoangluongtran0309.dbbackup.core.port.StorageProfileRepository;

@ExtendWith(MockitoExtension.class)
class ManageStorageProfileServiceTest {
    private static final Instant NOW = Instant.parse("2026-09-22T08:00:00Z");

    @Mock StorageProfileRepository profiles;
    @Mock DatabaseTargetRepository targets;
    @Mock BackupExecutionRepository backups;
    @Mock ArtifactStorageService artifacts;
    @Mock EncryptionPort encryption;
    ManageStorageProfileService service;

    @BeforeEach
    void setUp() {
        service = new ManageStorageProfileService(profiles, targets, backups, artifacts, encryption,
                Clock.fixed(NOW.plusSeconds(60), ZoneOffset.UTC));
    }

    @Test
    void blankSecretOnEditKeepsTheExistingEncryptedSecret() {
        StorageProfile current = profile();
        when(profiles.findById(current.getId())).thenReturn(Optional.of(current));
        when(profiles.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        StorageProfile edited = service.edit(current.getId(), command(
                "renamed", current.getEndpoint(), StorageCredentialMode.STATIC, "access-2", null));

        assertThat(edited.getName()).isEqualTo("renamed");
        assertThat(edited.getAccessKeyId()).isEqualTo("access-2");
        assertThat(edited.getSecretAccessKeyCiphertext()).isEqualTo("encrypted-secret");
        assertThat(edited.getLastConnectionCheck()).isNull();
        verify(encryption, never()).encrypt(any());
    }

    @Test
    void locationCannotChangeAfterAnyExecutionReferencesTheProfile() {
        StorageProfile current = profile();
        when(profiles.findById(current.getId())).thenReturn(Optional.of(current));
        when(backups.countForStorageProfile(current.getId())).thenReturn(1L);

        assertThatThrownBy(() -> service.edit(current.getId(), command(
                current.getName(), "https://different.example.test", StorageCredentialMode.STATIC,
                current.getAccessKeyId(), null)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("cannot change");
        verify(profiles, never()).save(any());
    }

    @Test
    void switchingToDefaultChainErasesStaticCredentials() {
        StorageProfile current = profile();
        when(profiles.findById(current.getId())).thenReturn(Optional.of(current));
        when(profiles.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        StorageProfile edited = service.edit(current.getId(), command(
                current.getName(), current.getEndpoint(), StorageCredentialMode.DEFAULT_CHAIN, null, null));

        assertThat(edited.getCredentialMode()).isEqualTo(StorageCredentialMode.DEFAULT_CHAIN);
        assertThat(edited.getAccessKeyId()).isNull();
        assertThat(edited.getSecretAccessKeyCiphertext()).isNull();
    }

    @Test
    void referencedProfileCannotBeDeleted() {
        StorageProfile current = profile();
        when(profiles.findById(current.getId())).thenReturn(Optional.of(current));
        when(targets.countForStorageProfile(current.getId())).thenReturn(1L);

        assertThatThrownBy(() -> service.delete(current.getId()))
                .isInstanceOf(StorageProfileInUseException.class);
        verify(profiles, never()).deleteById(any());
    }

    @Test
    void newStaticSecretIsEncryptedBeforePersistence() {
        when(encryption.encrypt("plain-secret")).thenReturn("sealed-secret");
        when(profiles.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        ArgumentCaptor<StorageProfile> saved = ArgumentCaptor.forClass(StorageProfile.class);

        service.create(command("archive", "http://s3.test:9090",
                StorageCredentialMode.STATIC, "access", "plain-secret"));

        verify(profiles).save(saved.capture());
        assertThat(saved.getValue().getSecretAccessKeyCiphertext()).isEqualTo("sealed-secret");
    }

    @Test
    void serviceAccountJsonIsEncryptedAndBlankEditKeepsIt() {
        when(encryption.encrypt("plain-json")).thenReturn("sealed-json");
        when(profiles.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        StorageProfile created = service.create(new SaveStorageProfileCommand("gcs", StorageProvider.GCS,
                null, null, "backup-project", "backups", "daily", false,
                StorageCredentialMode.SERVICE_ACCOUNT_JSON, null, null, "plain-json"));
        assertThat(created.getServiceAccountJsonCiphertext()).isEqualTo("sealed-json");

        when(profiles.findById(created.getId())).thenReturn(Optional.of(created));
        StorageProfile edited = service.edit(created.getId(), new SaveStorageProfileCommand("renamed",
                StorageProvider.GCS, null, null, "backup-project", "backups", "daily", false,
                StorageCredentialMode.SERVICE_ACCOUNT_JSON, null, null, null));
        assertThat(edited.getServiceAccountJsonCiphertext()).isEqualTo("sealed-json");
    }

    @Test
    void switchingGcsToApplicationDefaultErasesStoredJson() {
        StorageProfile current = gcsProfile(StorageCredentialMode.SERVICE_ACCOUNT_JSON, "sealed-json");
        when(profiles.findById(current.getId())).thenReturn(Optional.of(current));
        when(profiles.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        StorageProfile edited = service.edit(current.getId(), gcsCommand(
                StorageCredentialMode.APPLICATION_DEFAULT, null, "backup-project"));

        assertThat(edited.getCredentialMode()).isEqualTo(StorageCredentialMode.APPLICATION_DEFAULT);
        assertThat(edited.getServiceAccountJsonCiphertext()).isNull();
    }

    @Test
    void switchingGcsToJsonRequiresANewKey() {
        StorageProfile current = gcsProfile(StorageCredentialMode.APPLICATION_DEFAULT, null);
        when(profiles.findById(current.getId())).thenReturn(Optional.of(current));

        assertThatThrownBy(() -> service.edit(current.getId(), gcsCommand(
                StorageCredentialMode.SERVICE_ACCOUNT_JSON, null, "backup-project")))
                .hasMessageContaining("Service-account JSON is required");
    }

    @Test
    void referencedGcsProjectCannotChangeButCredentialsCanRotate() {
        StorageProfile current = gcsProfile(StorageCredentialMode.SERVICE_ACCOUNT_JSON, "sealed-json");
        when(profiles.findById(current.getId())).thenReturn(Optional.of(current));
        when(backups.countForStorageProfile(current.getId())).thenReturn(1L);

        assertThatThrownBy(() -> service.edit(current.getId(), gcsCommand(
                StorageCredentialMode.SERVICE_ACCOUNT_JSON, null, "different-project")))
                .hasMessageContaining("cannot change");
        verify(profiles, never()).save(any());
    }

    @Test
    void providerCannotChange() {
        StorageProfile current = profile();
        when(profiles.findById(current.getId())).thenReturn(Optional.of(current));
        assertThatThrownBy(() -> service.edit(current.getId(), new SaveStorageProfileCommand(
                current.getName(), StorageProvider.GCS, null, null, "project", "backups", "daily", false,
                StorageCredentialMode.APPLICATION_DEFAULT, null, null, null)))
                .hasMessageContaining("provider cannot be changed");
    }

    @Test
    void serviceAccountJsonIsLimitedByUtf8Bytes() {
        String oversized = "€".repeat(22_000);
        assertThatThrownBy(() -> new SaveStorageProfileCommand("gcs", StorageProvider.GCS,
                null, null, "project", "backups", "", false,
                StorageCredentialMode.SERVICE_ACCOUNT_JSON, null, null, oversized))
                .hasMessageContaining("64 KiB");
    }

    private static SaveStorageProfileCommand command(String name, String endpoint,
            StorageCredentialMode mode, String accessKey, String secret) {
        return new SaveStorageProfileCommand(name, StorageProvider.S3, endpoint, "us-east-1", null,
                "backups", "daily", true, mode, accessKey, secret, null);
    }

    private static SaveStorageProfileCommand gcsCommand(
            StorageCredentialMode mode, String json, String projectId) {
        return new SaveStorageProfileCommand("gcs", StorageProvider.GCS, null, null, projectId,
                "backups", "daily", false, mode, null, null, json);
    }

    private static StorageProfile profile() {
        return StorageProfile.builder().id(UUID.randomUUID()).name("archive").provider(StorageProvider.S3)
                .endpoint("http://s3.test:9090").region("us-east-1").bucket("backups")
                .keyPrefix("daily").pathStyle(true).credentialMode(StorageCredentialMode.STATIC)
                .accessKeyId("access").secretAccessKeyCiphertext("encrypted-secret")
                .createdAt(NOW).updatedAt(NOW).build();
    }

    private static StorageProfile gcsProfile(StorageCredentialMode mode, String encryptedJson) {
        return StorageProfile.builder().id(UUID.randomUUID()).name("gcs").provider(StorageProvider.GCS)
                .projectId("backup-project").bucket("backups").keyPrefix("daily")
                .credentialMode(mode).serviceAccountJsonCiphertext(encryptedJson)
                .createdAt(NOW).updatedAt(NOW).build();
    }
}
