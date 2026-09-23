package com.hoangluongtran0309.dbbackup.adapter.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.security.KeyPairGenerator;
import java.util.Base64;

import org.junit.jupiter.api.Test;

import com.google.cloud.NoCredentials;
import com.google.auth.oauth2.ServiceAccountCredentials;
import com.hoangluongtran0309.dbbackup.core.model.GcsStorageConnection;
import com.hoangluongtran0309.dbbackup.core.model.StorageCredentialMode;

class GcsStorageAdapterTest {

    @Test
    void resolvesApplicationDefaultCredentialsThroughTheDefaultProvider() throws Exception {
        var expected = NoCredentials.getInstance();
        GcsStorageConnection connection = new GcsStorageConnection(null, "backup-project", "backups", "",
                StorageCredentialMode.APPLICATION_DEFAULT, null);
        assertThat(GcsStorageAdapter.resolveCredentials(connection, () -> expected)).isSameAs(expected);
    }

    @Test
    void parsesOnlyServiceAccountJsonForJsonCredentialMode() throws Exception {
        var credentials = GcsStorageAdapter.resolveCredentials(connection(serviceAccountJson()));
        assertThat(credentials).isInstanceOf(ServiceAccountCredentials.class);
    }

    @Test
    void rejectsOtherGoogleCredentialJsonTypes() {
        String authorizedUser = """
                {"type":"authorized_user","client_id":"id","client_secret":"secret","refresh_token":"token"}
                """;
        assertThatThrownBy(() -> GcsStorageAdapter.resolveCredentials(connection(authorizedUser)))
                .isInstanceOf(Exception.class);
    }

    private static GcsStorageConnection connection(String json) {
        return new GcsStorageConnection(null, "backup-project", "backups", "",
                StorageCredentialMode.SERVICE_ACCOUNT_JSON, json);
    }

    private static String serviceAccountJson() throws Exception {
        var generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        byte[] privateKey = generator.generateKeyPair().getPrivate().getEncoded();
        String pem = "-----BEGIN PRIVATE KEY-----\n"
                + Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII)).encodeToString(privateKey)
                + "\n-----END PRIVATE KEY-----\n";
        return """
                {
                  "type": "service_account",
                  "project_id": "backup-project",
                  "private_key_id": "test-key",
                  "private_key": "%s",
                  "client_email": "backup@backup-project.iam.gserviceaccount.com",
                  "client_id": "1234567890",
                  "token_uri": "https://oauth2.googleapis.com/token"
                }
                """.formatted(pem.replace("\\", "\\\\").replace("\n", "\\n").replace("\"", "\\\""));
    }
}
