package com.hoangluongtran0309.dbbackup.web.controller;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.hoangluongtran0309.dbbackup.application.storage.ManageStorageProfileService;
import com.hoangluongtran0309.dbbackup.core.model.StorageCredentialMode;
import com.hoangluongtran0309.dbbackup.core.model.StorageProfile;
import com.hoangluongtran0309.dbbackup.core.model.StorageProvider;
import com.hoangluongtran0309.dbbackup.web.security.SecurityConfig;

@WebMvcTest(StorageProfileController.class)
@Import(SecurityConfig.class)
class StorageProfileControllerTest {
    @Autowired MockMvc mvc;
    @MockitoBean ManageStorageProfileService service;

    @Test
    void storageRequiresAuthentication() throws Exception {
        mvc.perform(get("/storage")).andExpect(status().is3xxRedirection());
    }

    @Test @WithMockUser
    void editNeverRendersTheEncryptedOrPlainSecret() throws Exception {
        UUID id = UUID.randomUUID();
        when(service.get(id)).thenReturn(profile(id));
        mvc.perform(get("/storage/{id}/edit", id))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Leave empty to keep")))
                .andExpect(content().string(not(containsString("ciphertext-never-in-html"))))
                .andExpect(content().string(not(containsString("plain-secret"))));
    }

    @Test @WithMockUser
    void mutationRequiresCsrf() throws Exception {
        mvc.perform(post("/storage").with(csrf().useInvalidToken()).param("name", "archive"))
                .andExpect(status().isForbidden());
    }

    @Test @WithMockUser
    void createsAStaticProfileWithCsrf() throws Exception {
        when(service.create(any())).thenReturn(profile(UUID.randomUUID()));
        mvc.perform(post("/storage").with(csrf())
                        .param("name", "archive").param("endpoint", "http://s3.test:9090")
                        .param("provider", "S3")
                        .param("region", "us-east-1").param("bucket", "backups")
                        .param("credentialMode", "STATIC").param("accessKeyId", "access")
                        .param("secretAccessKey", "plain-secret"))
                .andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/storage"));
        verify(service).create(any());
    }

    @Test @WithMockUser
    void validationFailureNeverEchoesSubmittedSecret() throws Exception {
        mvc.perform(post("/storage").with(csrf())
                        .param("name", "")
                        .param("provider", "S3")
                        .param("region", "us-east-1").param("bucket", "backups")
                        .param("credentialMode", "STATIC").param("accessKeyId", "access")
                        .param("secretAccessKey", "must-not-return-to-browser"))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("must-not-return-to-browser"))));
    }

    @Test @WithMockUser
    void gcsFormUsesApplicationDefaultCredentialsAndGcsFields() throws Exception {
        mvc.perform(get("/storage/new").param("provider", "GCS"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Google Cloud project ID")))
                .andExpect(content().string(containsString("Application Default Credentials")))
                .andExpect(content().string(containsString("name=\"serviceAccountJson\"")))
                .andExpect(content().string(not(containsString("name=\"region\""))))
                .andExpect(content().string(not(containsString("name=\"accessKeyId\""))));
    }

    @Test @WithMockUser
    void createsAGcsApplicationDefaultProfileWithCsrf() throws Exception {
        when(service.create(any())).thenReturn(gcsProfile(UUID.randomUUID()));
        mvc.perform(post("/storage").with(csrf())
                        .param("name", "gcs-archive").param("provider", "GCS")
                        .param("projectId", "backup-project").param("bucket", "backups")
                        .param("credentialMode", "APPLICATION_DEFAULT"))
                .andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/storage"));
        verify(service).create(any());
    }

    @Test @WithMockUser
    void gcsValidationFailureNeverEchoesServiceAccountJson() throws Exception {
        mvc.perform(post("/storage").with(csrf())
                        .param("name", "").param("provider", "GCS")
                        .param("projectId", "backup-project").param("bucket", "backups")
                        .param("credentialMode", "SERVICE_ACCOUNT_JSON")
                        .param("serviceAccountJson", "private-json-must-not-return"))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("private-json-must-not-return"))));
    }

    @Test @WithMockUser
    void editNeverRendersEncryptedGcsCredential() throws Exception {
        UUID id = UUID.randomUUID();
        when(service.get(id)).thenReturn(gcsProfile(id));
        mvc.perform(get("/storage/{id}/edit", id))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Leave empty to keep")))
                .andExpect(content().string(not(containsString("encrypted-json-never-in-html"))));
    }

    @Test @WithMockUser
    void azureFormUsesAzureDefaultCredentialsAndAzureFields() throws Exception {
        mvc.perform(get("/storage/new").param("provider", "AZURE_BLOB"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Storage account name")))
                .andExpect(content().string(containsString("Azure Default Credential")))
                .andExpect(content().string(containsString("name=\"accountKey\"")))
                .andExpect(content().string(containsString("Container")))
                .andExpect(content().string(not(containsString("name=\"region\""))))
                .andExpect(content().string(not(containsString("name=\"projectId\""))));
    }

    @Test @WithMockUser
    void createsAnAzureAccountKeyProfileWithCsrf() throws Exception {
        when(service.create(any())).thenReturn(azureProfile(UUID.randomUUID()));
        mvc.perform(post("/storage").with(csrf())
                        .param("name", "azure-archive").param("provider", "AZURE_BLOB")
                        .param("accountName", "backupaccount").param("bucket", "backups")
                        .param("credentialMode", "ACCOUNT_KEY").param("accountKey", "plain-key"))
                .andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/storage"));
        verify(service).create(any());
    }

    @Test @WithMockUser
    void azureValidationAndEditNeverEchoTheAccountKey() throws Exception {
        mvc.perform(post("/storage").with(csrf())
                        .param("name", "").param("provider", "AZURE_BLOB")
                        .param("accountName", "backupaccount").param("bucket", "backups")
                        .param("credentialMode", "ACCOUNT_KEY")
                        .param("accountKey", "plain-key-must-not-return"))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("plain-key-must-not-return"))));

        UUID id = UUID.randomUUID();
        when(service.get(id)).thenReturn(azureProfile(id));
        mvc.perform(get("/storage/{id}/edit", id))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Leave empty to keep")))
                .andExpect(content().string(not(containsString("encrypted-key-never-in-html"))));
    }

    @Test @WithMockUser
    void listsTheBuiltInDestinationAndProfiles() throws Exception {
        when(service.listAll()).thenReturn(List.of(profile(UUID.randomUUID())));
        mvc.perform(get("/storage")).andExpect(status().isOk())
                .andExpect(content().string(containsString("Local filesystem")))
                .andExpect(content().string(containsString("archive")));
    }

    @Test @WithMockUser
    void listsAzureProviderDefaultEndpointAndCredentialMode() throws Exception {
        when(service.listAll()).thenReturn(List.of(azureProfile(UUID.randomUUID())));

        mvc.perform(get("/storage")).andExpect(status().isOk())
                .andExpect(content().string(containsString("Azure Blob Storage")))
                .andExpect(content().string(containsString(
                        "https://backupaccount.blob.core.windows.net")))
                .andExpect(content().string(containsString("Storage account key")))
                .andExpect(content().string(not(containsString("encrypted-key-never-in-html"))));
    }

    private static StorageProfile profile(UUID id) {
        Instant now = Instant.parse("2026-09-22T08:00:00Z");
        return StorageProfile.builder().id(id).name("archive").provider(StorageProvider.S3)
                .endpoint("http://s3.test:9090")
                .region("us-east-1").bucket("backups").keyPrefix("daily").pathStyle(true)
                .credentialMode(StorageCredentialMode.STATIC).accessKeyId("access")
                .secretAccessKeyCiphertext("ciphertext-never-in-html")
                .createdAt(now).updatedAt(now).build();
    }

    private static StorageProfile gcsProfile(UUID id) {
        Instant now = Instant.parse("2026-09-22T08:00:00Z");
        return StorageProfile.builder().id(id).name("gcs-archive").provider(StorageProvider.GCS)
                .projectId("backup-project").bucket("backups").keyPrefix("daily")
                .credentialMode(StorageCredentialMode.SERVICE_ACCOUNT_JSON)
                .serviceAccountJsonCiphertext("encrypted-json-never-in-html")
                .createdAt(now).updatedAt(now).build();
    }

    private static StorageProfile azureProfile(UUID id) {
        Instant now = Instant.parse("2026-09-22T08:00:00Z");
        return StorageProfile.builder().id(id).name("azure-archive")
                .provider(StorageProvider.AZURE_BLOB).accountName("backupaccount")
                .bucket("backups").keyPrefix("daily").credentialMode(StorageCredentialMode.ACCOUNT_KEY)
                .accountKeyCiphertext("encrypted-key-never-in-html")
                .createdAt(now).updatedAt(now).build();
    }
}
