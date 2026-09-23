package com.hoangluongtran0309.dbbackup.adapter.storage;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.hoangluongtran0309.dbbackup.core.model.AzureBlobStorageConnection;
import com.hoangluongtran0309.dbbackup.core.model.StorageCredentialMode;

class AzureBlobStorageAdapterTest {

    @Test
    void derivesThePublicEndpointWhenNoOverrideIsConfigured() {
        AzureBlobStorageConnection connection = new AzureBlobStorageConnection(null, "backupaccount",
                "backups", "", StorageCredentialMode.AZURE_DEFAULT, null);
        assertThat(AzureBlobStorageAdapter.endpointFor(connection))
                .isEqualTo("https://backupaccount.blob.core.windows.net");
    }

    @Test
    void keepsAnExplicitEmulatorEndpoint() {
        AzureBlobStorageConnection connection = new AzureBlobStorageConnection(
                "http://azurite:10000/devstoreaccount1", "devstoreaccount1", "backups", "",
                StorageCredentialMode.ACCOUNT_KEY, "key");
        assertThat(AzureBlobStorageAdapter.endpointFor(connection))
                .isEqualTo("http://azurite:10000/devstoreaccount1");
    }
}
