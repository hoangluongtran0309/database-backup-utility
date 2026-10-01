package com.hoangluongtran0309.dbbackup.web.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.hoangluongtran0309.dbbackup.application.backup.BackupArtifactService;
import com.hoangluongtran0309.dbbackup.application.backup.RunBackupService;
import com.hoangluongtran0309.dbbackup.application.notification.ManageNotificationChannelService;
import com.hoangluongtran0309.dbbackup.application.notification.ManageTargetNotificationService;
import com.hoangluongtran0309.dbbackup.application.restore.RestoreBackupService;
import com.hoangluongtran0309.dbbackup.application.retention.ManageBackupRetentionService;
import com.hoangluongtran0309.dbbackup.application.schedule.ManageBackupScheduleService;
import com.hoangluongtran0309.dbbackup.application.storage.ManageStorageProfileService;
import com.hoangluongtran0309.dbbackup.application.target.ManageDatabaseTargetService;
import com.hoangluongtran0309.dbbackup.application.target.RegisterTargetCommand;
import com.hoangluongtran0309.dbbackup.application.target.TestTargetConnectionService;
import com.hoangluongtran0309.dbbackup.application.verification.RestoreVerificationService;
import com.hoangluongtran0309.dbbackup.core.model.DatabaseEngine;
import com.hoangluongtran0309.dbbackup.core.model.DatabaseTarget;
import com.hoangluongtran0309.dbbackup.core.model.NotificationChannel;
import com.hoangluongtran0309.dbbackup.core.model.NotificationChannelType;
import com.hoangluongtran0309.dbbackup.core.model.StorageCredentialMode;
import com.hoangluongtran0309.dbbackup.core.model.StorageProfile;
import com.hoangluongtran0309.dbbackup.core.model.StorageProvider;
import com.hoangluongtran0309.dbbackup.core.port.BackupExecutionRepository;
import com.hoangluongtran0309.dbbackup.core.port.RestoreExecutionRepository;
import com.hoangluongtran0309.dbbackup.core.port.RestoreVerificationExecutionRepository;
import com.hoangluongtran0309.dbbackup.web.security.SecurityConfig;

@WebMvcTest(OperatorApiController.class)
@Import({SecurityConfig.class, ApiExceptionHandler.class})
@WithMockUser(roles = "OPERATOR")
class OperatorApiControllerTest {
    @Autowired MockMvc mvc;

    @MockitoBean ManageDatabaseTargetService targets;
    @MockitoBean TestTargetConnectionService targetConnections;
    @MockitoBean ManageStorageProfileService storage;
    @MockitoBean ManageNotificationChannelService channels;
    @MockitoBean ManageTargetNotificationService subscriptions;
    @MockitoBean ManageBackupScheduleService schedules;
    @MockitoBean ManageBackupRetentionService retention;
    @MockitoBean RunBackupService backupRunner;
    @MockitoBean BackupExecutionRepository backups;
    @MockitoBean BackupArtifactService artifacts;
    @MockitoBean RestoreBackupService restoreRunner;
    @MockitoBean RestoreExecutionRepository restores;
    @MockitoBean RestoreVerificationService verificationRunner;
    @MockitoBean RestoreVerificationExecutionRepository verifications;

    @Test
    void listsOnlyTheSafeTargetProjection() throws Exception {
        DatabaseTarget target = target("production");
        when(targets.listAll()).thenReturn(List.of(target));

        mvc.perform(get("/api/v1/targets"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true))
                .andExpect(jsonPath("$.data[0].id").value(target.getId().toString()))
                .andExpect(jsonPath("$.data[0].name").value("production"))
                .andExpect(jsonPath("$.data[0].passwordCiphertext").doesNotExist());
    }

    @Test
    void startsABackupWithoutBrowserCsrfAndReturnsAcceptedExecutionId() throws Exception {
        UUID targetId = UUID.randomUUID();
        UUID executionId = UUID.randomUUID();
        when(backupRunner.start(targetId)).thenReturn(executionId);

        mvc.perform(post("/api/v1/targets/{id}/backups", targetId))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.executionId").value(executionId.toString()));
    }

    @Test
    void storageAndNotificationResponsesOnlyExposeWhetherASecretExists() throws Exception {
        Instant now = Instant.parse("2026-09-26T01:00:00Z");
        StorageProfile profile = StorageProfile.builder().id(UUID.randomUUID()).name("archive")
                .provider(StorageProvider.S3).region("us-east-1").bucket("backups")
                .credentialMode(StorageCredentialMode.STATIC).accessKeyId("key-id")
                .secretAccessKeyCiphertext("storage-ciphertext-must-not-leak")
                .createdAt(now).updatedAt(now).build();
        NotificationChannel channel = NotificationChannel.builder().id(UUID.randomUUID()).name("ops")
                .type(NotificationChannelType.WEBHOOK)
                .webhookUrlCiphertext("webhook-ciphertext-must-not-leak")
                .createdAt(now).updatedAt(now).build();
        when(storage.listAll()).thenReturn(List.of(profile));
        when(channels.listAll()).thenReturn(List.of(channel));

        mvc.perform(get("/api/v1/storage-profiles"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].secretConfigured").value(true))
                .andExpect(jsonPath("$.data[0].secretAccessKeyCiphertext").doesNotExist());
        mvc.perform(get("/api/v1/notification-channels"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].secretConfigured").value(true))
                .andExpect(jsonPath("$.data[0].webhookUrlCiphertext").doesNotExist());
    }

    @Test
    void restoreRequiresTheDestinationNameAndUsesTheJsonErrorEnvelope() throws Exception {
        UUID backupId = UUID.randomUUID();
        DatabaseTarget destination = target("disaster-recovery");
        when(targets.get(destination.getId())).thenReturn(destination);

        mvc.perform(post("/api/v1/restores")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"backupExecutionId":"%s","targetId":"%s","confirmation":"wrong"}
                                """.formatted(backupId, destination.getId())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.ok").value(false))
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));

        verify(restoreRunner, never()).start(backupId, destination.getId());
    }

    @Test
    void rejectsUnboundedHistoryPages() throws Exception {
        mvc.perform(get("/api/v1/backups").param("pageSize", "101"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
    }

    @Test
    void omittedAndNullVerifyAfterBackupBothDefaultToFalse() throws Exception {
        when(targets.register(any())).thenReturn(target("production"));

        mvc.perform(post("/api/v1/targets")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"production","engine":"MYSQL","host":"db.internal","port":3306,
                                 "database":"shop","username":"backup","password":"secret"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.verifyAfterBackup").value(false));
        mvc.perform(post("/api/v1/targets")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"production","engine":"MYSQL","host":"db.internal","port":3306,
                                 "database":"shop","username":"backup","password":"secret",
                                 "verifyAfterBackup":null}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.verifyAfterBackup").value(false));

        ArgumentCaptor<RegisterTargetCommand> commands = ArgumentCaptor.forClass(RegisterTargetCommand.class);
        verify(targets, times(2)).register(commands.capture());
        assertThat(commands.getAllValues()).allMatch(command -> !command.verifyAfterBackup());
    }

    @Test
    void targetValidationReturnsEveryErrorInFieldOrderAndKeepsLegacyFields() throws Exception {
        mvc.perform(post("/api/v1/targets")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"","engine":"MYSQL"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.error.message").value("Name is required"))
                .andExpect(jsonPath("$.error.field").value("name"))
                .andExpect(jsonPath("$.error.errors.length()").value(6))
                .andExpect(jsonPath("$.error.errors[0].field").value("name"))
                .andExpect(jsonPath("$.error.errors[1].field").value("host"))
                .andExpect(jsonPath("$.error.errors[2].field").value("port"))
                .andExpect(jsonPath("$.error.errors[3].field").value("database"))
                .andExpect(jsonPath("$.error.errors[4].field").value("username"))
                .andExpect(jsonPath("$.error.errors[5].field").value("password"));

        verify(targets, never()).register(any());
    }

    @Test
    void malformedJsonDoesNotExposeJacksonInternals() throws Exception {
        mvc.perform(post("/api/v1/targets")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{not-json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.message").value("Request body is not valid JSON"))
                .andExpect(jsonPath("$.error.message").value(
                        org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("Jackson"))));
    }

    @Test
    void otherWriteRequestFamiliesAlsoReturnAggregatedFieldErrors() throws Exception {
        mvc.perform(post("/api/v1/storage-profiles")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.errors.length()").value(4));
        mvc.perform(post("/api/v1/notification-channels")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.errors.length()").value(2));
        mvc.perform(post("/api/v1/schedules")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.errors.length()").value(4));
        mvc.perform(post("/api/v1/restores")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.errors.length()").value(3));
    }

    @Test
    void missingArtifactDownloadKeepsTheJsonErrorContract() throws Exception {
        UUID backupId = UUID.randomUUID();
        when(artifacts.download(backupId)).thenThrow(new NoSuchElementException("No backup execution"));

        mvc.perform(get("/api/v1/backups/{id}/artifact", backupId)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.ok").value(false))
                .andExpect(jsonPath("$.error.code").value("RESOURCE_NOT_FOUND"))
                .andExpect(jsonPath("$.error.errors").doesNotExist());
    }

    private static DatabaseTarget target(String name) {
        return DatabaseTarget.builder().id(UUID.randomUUID()).name(name).engine(DatabaseEngine.MYSQL)
                .host("db.internal").port(3306).databaseName("shop").username("backup")
                .passwordCiphertext("ciphertext-must-never-reach-json")
                .createdAt(Instant.parse("2026-09-26T01:00:00Z")).build();
    }
}
