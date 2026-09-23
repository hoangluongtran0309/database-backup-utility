package com.hoangluongtran0309.dbbackup.adapter.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import com.hoangluongtran0309.dbbackup.adapter.TestAdaptersApplication;
import com.hoangluongtran0309.dbbackup.core.exception.StorageProfileInUseException;
import com.hoangluongtran0309.dbbackup.core.model.BackupExecution;
import com.hoangluongtran0309.dbbackup.core.model.DatabaseEngine;
import com.hoangluongtran0309.dbbackup.core.model.DatabaseTarget;
import com.hoangluongtran0309.dbbackup.core.model.StorageCredentialMode;
import com.hoangluongtran0309.dbbackup.core.model.StorageProfile;
import com.hoangluongtran0309.dbbackup.core.model.StorageProvider;
import com.hoangluongtran0309.dbbackup.core.port.BackupExecutionRepository;
import com.hoangluongtran0309.dbbackup.core.port.DatabaseTargetRepository;
import com.hoangluongtran0309.dbbackup.core.port.StorageProfileRepository;

@SpringBootTest(classes = TestAdaptersApplication.class)
@Testcontainers
class StorageProfileRepositoryAdapterIT {
    private static final Instant NOW = Instant.parse("2026-09-22T08:00:00Z");
    @Container static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");
    @DynamicPropertySource static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired StorageProfileRepository profiles;
    @Autowired DatabaseTargetRepository targets;
    @Autowired BackupExecutionRepository backups;
    @Autowired JdbcTemplate jdbc;

    @Test
    void roundTripsEncryptedStaticCredentialsAndConfiguration() {
        StorageProfile saved = profiles.save(profile("roundtrip-" + UUID.randomUUID()));
        StorageProfile found = profiles.findById(saved.getId()).orElseThrow();
        assertThat(found.getEndpoint()).isEqualTo("http://s3.test:9090");
        assertThat(found.getCredentialMode()).isEqualTo(StorageCredentialMode.STATIC);
        assertThat(found.getSecretAccessKeyCiphertext()).isEqualTo("encrypted-secret");
        assertThat(found.isPathStyle()).isTrue();
        profiles.deleteById(saved.getId());
    }

    @Test
    void roundTripsGcsApplicationDefaultConfiguration() {
        StorageProfile profile = StorageProfile.builder().id(UUID.randomUUID())
                .name("gcs-" + UUID.randomUUID()).provider(StorageProvider.GCS)
                .endpoint("http://gcs.test:4443").projectId("backup-project")
                .bucket("backups").keyPrefix("daily")
                .credentialMode(StorageCredentialMode.APPLICATION_DEFAULT)
                .createdAt(NOW).updatedAt(NOW).build();
        StorageProfile found = profiles.findById(profiles.save(profile).getId()).orElseThrow();
        assertThat(found.getProvider()).isEqualTo(StorageProvider.GCS);
        assertThat(found.getProjectId()).isEqualTo("backup-project");
        assertThat(found.getRegion()).isNull();
        assertThat(found.getServiceAccountJsonCiphertext()).isNull();
        profiles.deleteById(found.getId());
    }

    @Test
    void roundTripsEncryptedGcsServiceAccountConfiguration() {
        StorageProfile profile = StorageProfile.builder().id(UUID.randomUUID())
                .name("gcs-json-" + UUID.randomUUID()).provider(StorageProvider.GCS)
                .projectId("backup-project").bucket("backups").keyPrefix("daily")
                .credentialMode(StorageCredentialMode.SERVICE_ACCOUNT_JSON)
                .serviceAccountJsonCiphertext("encrypted-service-account")
                .createdAt(NOW).updatedAt(NOW).build();
        StorageProfile found = profiles.findById(profiles.save(profile).getId()).orElseThrow();
        assertThat(found.getCredentialMode()).isEqualTo(StorageCredentialMode.SERVICE_ACCOUNT_JSON);
        assertThat(found.getServiceAccountJsonCiphertext()).isEqualTo("encrypted-service-account");
        profiles.deleteById(found.getId());
    }

    @Test
    void databaseRejectsAProviderConfigurationWithFieldsFromTheOtherProvider() {
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO storage_profiles
                    (id, name, provider, endpoint_url, region, project_id, bucket, key_prefix,
                     path_style, credential_mode, created_at, updated_at)
                VALUES (?, ?, 'GCS', NULL, 'us-east-1', 'backup-project', 'backups', '',
                        FALSE, 'APPLICATION_DEFAULT', now(), now())
                """, UUID.randomUUID(), "invalid-gcs-" + UUID.randomUUID()))
                .hasMessageContaining("chk_storage_profiles_configuration");
    }

    @Test
    void targetReferenceRestrictsDeletion() {
        StorageProfile profile = profiles.save(profile("target-ref-" + UUID.randomUUID()));
        DatabaseTarget target = targets.save(target(profile.getId(), "target-" + UUID.randomUUID()));
        assertThat(targets.countForStorageProfile(profile.getId())).isEqualTo(1);
        assertThatThrownBy(() -> profiles.deleteById(profile.getId()))
                .isInstanceOf(StorageProfileInUseException.class);
        targets.deleteById(target.getId());
        profiles.deleteById(profile.getId());
    }

    @Test
    void artifactReferenceRestrictsDeletionAndRoundTripsSnapshot() {
        StorageProfile profile = profiles.save(profile("artifact-ref-" + UUID.randomUUID()));
        DatabaseTarget target = targets.save(target(null, "artifact-target-" + UUID.randomUUID()));
        BackupExecution execution = BackupExecution.started(UUID.randomUUID(), target.getId(), profile.getId(), NOW)
                .succeeded("daily/t/e/shop.sql.gz", 42, "a".repeat(64), NOW.plusSeconds(2));
        backups.save(execution);
        BackupExecution found = backups.findById(execution.getId()).orElseThrow();
        assertThat(found.getStorageProfileId()).isEqualTo(profile.getId());
        assertThat(found.getArtifactLocator()).isEqualTo("daily/t/e/shop.sql.gz");
        assertThatThrownBy(() -> profiles.deleteById(profile.getId()))
                .isInstanceOf(StorageProfileInUseException.class);
        backups.deleteById(execution.getId());
        targets.deleteById(target.getId());
        profiles.deleteById(profile.getId());
    }

    private static StorageProfile profile(String name) {
        return StorageProfile.builder().id(UUID.randomUUID()).name(name).provider(StorageProvider.S3)
                .endpoint("http://s3.test:9090")
                .region("us-east-1").bucket("backups").keyPrefix("daily").pathStyle(true)
                .credentialMode(StorageCredentialMode.STATIC).accessKeyId("access")
                .secretAccessKeyCiphertext("encrypted-secret").createdAt(NOW).updatedAt(NOW).build();
    }

    private static DatabaseTarget target(UUID profileId, String name) {
        return DatabaseTarget.builder().id(UUID.randomUUID()).name(name).engine(DatabaseEngine.MYSQL)
                .host("localhost").port(3306).databaseName("shop").username("backup")
                .passwordCiphertext("sealed").storageProfileId(profileId).createdAt(NOW).build();
    }
}
