package com.hoangluongtran0309.dbbackup.core.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class StorageProfileTest {
    private static final Instant NOW = Instant.parse("2026-09-22T08:00:00Z");

    @Test
    void normalizesEndpointAndPrefix() {
        StorageProfile profile = staticProfile(" https://s3.example.test:9443/path ", "/daily/backups/");
        assertThat(profile.getEndpoint()).isEqualTo("https://s3.example.test:9443/path");
        assertThat(profile.getKeyPrefix()).isEqualTo("daily/backups");
    }

    @Test
    void rejectsEndpointPartsThatCouldChangeRequestMeaning() {
        assertThatThrownBy(() -> staticProfile("https://user@s3.example.test/bucket?q=x#part", ""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no user-info");
    }

    @Test
    void staticCredentialsRequireBothValues() {
        assertThatThrownBy(() -> StorageProfile.builder().id(UUID.randomUUID()).name("archive")
                .region("us-east-1").bucket("backups").credentialMode(StorageCredentialMode.STATIC)
                .accessKeyId("key").createdAt(NOW).updatedAt(NOW).build())
                .hasMessageContaining("Secret access key");
    }

    @Test
    void defaultChainCannotPersistStaticCredentials() {
        assertThatThrownBy(() -> StorageProfile.builder().id(UUID.randomUUID()).name("archive")
                .region("us-east-1").bucket("backups").credentialMode(StorageCredentialMode.DEFAULT_CHAIN)
                .accessKeyId("key").createdAt(NOW).updatedAt(NOW).build())
                .hasMessageContaining("must not store static credentials");
    }

    @Test
    void artifactReferenceDistinguishesLocalAndManagedStorage() {
        assertThat(new ArtifactReference(null, "/backups/a.sql.gz").isLocal()).isTrue();
        assertThat(new ArtifactReference(UUID.randomUUID(), "prefix/target/run/a.sql.gz").isLocal()).isFalse();
    }

    @Test
    void shortLivedConnectionNeverPrintsCredentials() {
        S3StorageConnection connection = new S3StorageConnection("https://s3.example.test", "us-east-1",
                "backups", "daily", true, StorageCredentialMode.STATIC,
                "access-must-not-be-logged", "secret-must-not-be-logged");
        assertThat(connection.toString()).doesNotContain("access-must-not-be-logged", "secret-must-not-be-logged");
    }

    private static StorageProfile staticProfile(String endpoint, String prefix) {
        return StorageProfile.builder().id(UUID.randomUUID()).name("archive").endpoint(endpoint)
                .region("us-east-1").bucket("backups").keyPrefix(prefix).pathStyle(true)
                .credentialMode(StorageCredentialMode.STATIC).accessKeyId("key")
                .secretAccessKeyCiphertext("encrypted").createdAt(NOW).updatedAt(NOW).build();
    }
}
