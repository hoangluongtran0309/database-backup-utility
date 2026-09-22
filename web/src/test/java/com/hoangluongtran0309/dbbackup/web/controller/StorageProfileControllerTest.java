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
                        .param("region", "us-east-1").param("bucket", "backups")
                        .param("credentialMode", "STATIC").param("accessKeyId", "access")
                        .param("secretAccessKey", "must-not-return-to-browser"))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("must-not-return-to-browser"))));
    }

    @Test @WithMockUser
    void listsTheBuiltInDestinationAndProfiles() throws Exception {
        when(service.listAll()).thenReturn(List.of(profile(UUID.randomUUID())));
        mvc.perform(get("/storage")).andExpect(status().isOk())
                .andExpect(content().string(containsString("Local filesystem")))
                .andExpect(content().string(containsString("archive")));
    }

    private static StorageProfile profile(UUID id) {
        Instant now = Instant.parse("2026-09-22T08:00:00Z");
        return StorageProfile.builder().id(id).name("archive").endpoint("http://s3.test:9090")
                .region("us-east-1").bucket("backups").keyPrefix("daily").pathStyle(true)
                .credentialMode(StorageCredentialMode.STATIC).accessKeyId("access")
                .secretAccessKeyCiphertext("ciphertext-never-in-html")
                .createdAt(now).updatedAt(now).build();
    }
}
