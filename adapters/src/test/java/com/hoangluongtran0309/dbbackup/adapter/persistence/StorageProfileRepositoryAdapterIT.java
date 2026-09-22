package com.hoangluongtran0309.dbbackup.adapter.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
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
        return StorageProfile.builder().id(UUID.randomUUID()).name(name).endpoint("http://s3.test:9090")
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
