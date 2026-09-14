package com.hoangluongtran0309.dbbackup.web.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import java.time.Instant;
import java.util.NoSuchElementException;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.hoangluongtran0309.dbbackup.application.backup.RunBackupService;
import com.hoangluongtran0309.dbbackup.application.target.EditTargetCommand;
import com.hoangluongtran0309.dbbackup.application.target.ManageDatabaseTargetService;
import com.hoangluongtran0309.dbbackup.application.target.TestTargetConnectionService;
import com.hoangluongtran0309.dbbackup.core.exception.DuplicateTargetNameException;
import com.hoangluongtran0309.dbbackup.core.exception.TargetInUseException;
import com.hoangluongtran0309.dbbackup.core.model.BackupExecution;
import com.hoangluongtran0309.dbbackup.core.model.ConnectionCheck;
import com.hoangluongtran0309.dbbackup.core.model.DatabaseTarget;
import com.hoangluongtran0309.dbbackup.core.port.BackupExecutionRepository;
import com.hoangluongtran0309.dbbackup.web.security.SecurityConfig;

@WebMvcTest(DatabaseTargetController.class)
// The console's real rules: signed in, and every POST carries a CSRF token.
@Import(SecurityConfig.class)
@WithMockUser
class DatabaseTargetControllerTest {

    private static final Instant CHECKED_AT = Instant.parse("2026-09-09T11:00:00Z");
    private static final String SHA256 = "9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08";
    private static final Instant BACKED_UP_AT = Instant.parse("2026-09-09T08:00:00Z");

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ManageDatabaseTargetService service;

    @MockitoBean
    private TestTargetConnectionService connectionTest;

    @MockitoBean
    private RunBackupService backups;

    @MockitoBean
    private BackupExecutionRepository executions;

    @Test
    void listsTargets() throws Exception {
        when(service.listAll()).thenReturn(List.of(target("production")));

        mockMvc.perform(get("/databases"))
                .andExpect(status().isOk())
                .andExpect(view().name("database/list"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("production")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("127.0.0.1:3306/shop")));
    }

    /**
     * Also the proof that th:action puts the CSRF token in a form — every form
     * in the console relies on it, and a form without one is refused.
     */
    @Test
    void saysWhoIsSignedInAndOffersToSignOut() throws Exception {
        when(service.listAll()).thenReturn(List.of(target("production")));

        mockMvc.perform(get("/databases"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Signed in as <strong>user</strong>")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("action=\"/logout\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("name=\"_csrf\"")));
    }

    @Test
    void saysNeverForATargetThatHasNoBackups() throws Exception {
        when(service.listAll()).thenReturn(List.of(target("production")));
        when(executions.findAllNewestFirst()).thenReturn(List.of());

        mockMvc.perform(get("/databases"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString(">Never</span>")));
    }

    /**
     * The newest good copy is what an operator is looking for, so a failed
     * attempt after it must not hide it — it is flagged alongside instead.
     */
    @Test
    void showsTheLastSuccessfulBackupEvenWhenANewerAttemptFailed() throws Exception {
        DatabaseTarget target = target("production");
        BackupExecution good = BackupExecution.started(UUID.randomUUID(), target.getId(), BACKED_UP_AT)
                .succeeded("/backups/shop.sql.gz", 8192, SHA256, BACKED_UP_AT.plusSeconds(5));
        BackupExecution failed = BackupExecution.started(UUID.randomUUID(), target.getId(), BACKED_UP_AT.plusSeconds(3600))
                .failed("Access denied", BACKED_UP_AT.plusSeconds(3601));
        when(service.listAll()).thenReturn(List.of(target));
        when(executions.findAllNewestFirst()).thenReturn(List.of(failed, good));

        mockMvc.perform(get("/databases"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("2026-09-09 08:00 UTC")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("/executions/" + good.getId())))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Latest attempt failed")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("/executions/" + failed.getId())))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString(">Never</span>"))));
    }

    @Test
    void flagsABackupThatIsStillRunning() throws Exception {
        DatabaseTarget target = target("production");
        when(service.listAll()).thenReturn(List.of(target));
        when(executions.findAllNewestFirst()).thenReturn(List.of(
                BackupExecution.started(UUID.randomUUID(), target.getId(), BACKED_UP_AT)));

        mockMvc.perform(get("/databases"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString(">Running</a>")))
                // Not "Never": a first backup is on its way.
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString(">Never</span>"))));
    }

    @Test
    void showsAnEmptyStateWhenThereAreNoTargets() throws Exception {
        when(service.listAll()).thenReturn(List.of());

        mockMvc.perform(get("/databases"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("No targets yet")));
    }

    @Test
    void newFormDefaultsToTheMysqlPort() throws Exception {
        mockMvc.perform(get("/databases/new"))
                .andExpect(status().isOk())
                .andExpect(view().name("database/form"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("value=\"3306\"")));
    }

    @Test
    void registersAValidTargetAndRedirects() throws Exception {
        when(service.register(any())).thenReturn(target("production"));

        mockMvc.perform(post("/databases").with(csrf())
                        .param("name", "production")
                        .param("host", "127.0.0.1")
                        .param("port", "3306")
                        .param("database", "shop")
                        .param("username", "backup")
                        .param("password", "s3cr3t"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/databases"));

        verify(service).register(any());
    }

    @Test
    void redisplaysTheFormWithTheTypedValuesWhenAFieldIsInvalid() throws Exception {
        mockMvc.perform(post("/databases").with(csrf())
                        .param("name", "")
                        .param("host", "db.internal")
                        .param("port", "3306")
                        .param("database", "shop")
                        .param("username", "backup")
                        .param("password", "s3cr3t"))
                .andExpect(status().isOk())
                .andExpect(view().name("database/form"))
                .andExpect(model().attributeHasFieldErrors("form", "name"))
                // What the operator already typed must survive the round trip.
                .andExpect(content().string(org.hamcrest.Matchers.containsString("value=\"db.internal\"")));

        verify(service, never()).register(any());
    }

    @Test
    void reportsADuplicateNameOnTheNameField() throws Exception {
        when(service.register(any())).thenThrow(new DuplicateTargetNameException("production"));

        mockMvc.perform(post("/databases").with(csrf())
                        .param("name", "production")
                        .param("host", "127.0.0.1")
                        .param("port", "3306")
                        .param("database", "shop")
                        .param("username", "backup")
                        .param("password", "s3cr3t"))
                .andExpect(status().isOk())
                .andExpect(view().name("database/form"))
                .andExpect(model().attributeHasFieldErrors("form", "name"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("already exists")));
    }

    @Test
    void neverRendersTheSubmittedPasswordBackIntoThePage() throws Exception {
        mockMvc.perform(post("/databases").with(csrf())
                        .param("name", "")
                        .param("host", "127.0.0.1")
                        .param("port", "3306")
                        .param("database", "shop")
                        .param("username", "backup")
                        .param("password", "s3cr3t"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("s3cr3t"))));
    }

    @Test
    void theEditFormIsFilledInExceptForThePassword() throws Exception {
        DatabaseTarget target = target("production");
        when(service.get(target.getId())).thenReturn(target);

        mockMvc.perform(get("/databases/{id}/edit", target.getId()))
                .andExpect(status().isOk())
                .andExpect(view().name("database/form"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("value=\"production\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("value=\"127.0.0.1\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "action=\"/databases/" + target.getId() + "\"")))
                // The schema is shown read-only and has no name, so it is never sent.
                .andExpect(content().string(org.hamcrest.Matchers.containsString("readonly value=\"shop\"")))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("name=\"database\""))))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("Y2lwaGVydGV4dA=="))))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Save changes")));
    }

    @Test
    void editingATargetThatIsGoneGoesBackToTheList() throws Exception {
        UUID id = UUID.randomUUID();
        when(service.get(id)).thenThrow(new NoSuchElementException("gone"));

        mockMvc.perform(get("/databases/{id}/edit", id))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/databases"))
                .andExpect(flash().attribute("error", "That target no longer exists"));
    }

    @Test
    void savesAnEditAndRedirectsWithAnEmptyPasswordMeaningUnchanged() throws Exception {
        DatabaseTarget target = target("production");
        when(service.get(target.getId())).thenReturn(target);
        when(service.edit(eq(target.getId()), any())).thenReturn(target("staging"));

        mockMvc.perform(post("/databases/{id}", target.getId()).with(csrf())
                        .param("name", "staging")
                        .param("host", "10.0.0.5")
                        .param("port", "3307")
                        .param("username", "reader")
                        .param("password", ""))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/databases"))
                .andExpect(flash().attribute("message", "Saved target 'staging'"));

        verify(service).edit(target.getId(), new EditTargetCommand("staging", "10.0.0.5", 3307, "reader", ""));
    }

    @Test
    void anInvalidEditRedisplaysTheFormStillInEditMode() throws Exception {
        DatabaseTarget target = target("production");
        when(service.get(target.getId())).thenReturn(target);

        mockMvc.perform(post("/databases/{id}", target.getId()).with(csrf())
                        .param("name", "production")
                        .param("host", "")
                        .param("port", "3306")
                        .param("username", "backup"))
                .andExpect(status().isOk())
                .andExpect(view().name("database/form"))
                .andExpect(model().attributeHasFieldErrors("form", "host"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("readonly value=\"shop\"")));

        verify(service, never()).edit(any(), any());
    }

    @Test
    void renamingOntoATakenNameIsReportedOnTheNameField() throws Exception {
        DatabaseTarget target = target("staging");
        when(service.get(target.getId())).thenReturn(target);
        when(service.edit(eq(target.getId()), any())).thenThrow(new DuplicateTargetNameException("production"));

        mockMvc.perform(post("/databases/{id}", target.getId()).with(csrf())
                        .param("name", "production")
                        .param("host", "127.0.0.1")
                        .param("port", "3306")
                        .param("username", "backup"))
                .andExpect(status().isOk())
                .andExpect(model().attributeHasFieldErrors("form", "name"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("already exists")));
    }

    @Test
    void anEditWithTheWrongCsrfTokenIsRefused() throws Exception {
        mockMvc.perform(post("/databases/{id}", UUID.randomUUID()).with(csrf().useInvalidToken())
                        .param("name", "production")
                        .param("host", "127.0.0.1")
                        .param("port", "3306")
                        .param("username", "backup"))
                .andExpect(status().isForbidden());

        verify(service, never()).edit(any(), any());
    }

    @Test
    void everyTargetOffersAnEditLink() throws Exception {
        DatabaseTarget target = target("production");
        when(service.listAll()).thenReturn(List.of(target));

        mockMvc.perform(get("/databases"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "href=\"/databases/" + target.getId() + "/edit\"")));
    }

    @Test
    void showsNeverTestedForATargetThatHasNotBeenProbed() throws Exception {
        when(service.listAll()).thenReturn(List.of(target("production")));

        mockMvc.perform(get("/databases"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Never tested")));
    }

    @Test
    void showsTheStoredFailureMessageOnTheListPage() throws Exception {
        when(service.listAll()).thenReturn(List.of(tested(ConnectionCheck.failed(
                "ERROR 1045 (28000): Access denied for user 'backup'", CHECKED_AT))));

        mockMvc.perform(get("/databases"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Failed")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Access denied for user")));
    }

    @Test
    void showsConnectedForATargetThatPassed() throws Exception {
        when(service.listAll()).thenReturn(List.of(tested(ConnectionCheck.passed(CHECKED_AT))));

        mockMvc.perform(get("/databases"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Connected")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("2026-09-09 11:00")));
    }

    @Test
    void testingATargetReportsSuccessAndRedirects() throws Exception {
        UUID id = UUID.randomUUID();
        when(connectionTest.test(id)).thenReturn(ConnectionCheck.passed(CHECKED_AT));

        mockMvc.perform(post("/databases/{id}/test", id).with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/databases"))
                .andExpect(flash().attribute("message", "Connection succeeded"));
    }

    @Test
    void testingATargetSurfacesMysqlsMessageOnFailure() throws Exception {
        UUID id = UUID.randomUUID();
        when(connectionTest.test(id)).thenReturn(
                ConnectionCheck.failed("ERROR 1045 (28000): Access denied", CHECKED_AT));

        mockMvc.perform(post("/databases/{id}/test", id).with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attribute("error",
                        "Connection failed: ERROR 1045 (28000): Access denied"));
    }

    @Test
    void testingATargetDeletedInAnotherTabDoesNotBlowUp() throws Exception {
        UUID id = UUID.randomUUID();
        when(connectionTest.test(id)).thenThrow(new NoSuchElementException("gone"));

        mockMvc.perform(post("/databases/{id}/test", id).with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/databases"))
                .andExpect(flash().attribute("error", "That target no longer exists"));
    }

    /**
     * The redirect goes to the execution, not back to the list: the row exists
     * before the dump starts, so there is always somewhere to send the browser.
     */
    @Test
    void startingABackupRedirectsToTheNewExecution() throws Exception {
        UUID targetId = UUID.randomUUID();
        UUID executionId = UUID.randomUUID();
        when(backups.start(targetId)).thenReturn(executionId);

        mockMvc.perform(post("/databases/{id}/backup", targetId).with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/executions/" + executionId));
    }

    @Test
    void startingABackupForATargetDeletedInAnotherTabDoesNotBlowUp() throws Exception {
        UUID targetId = UUID.randomUUID();
        when(backups.start(targetId)).thenThrow(new NoSuchElementException("gone"));

        mockMvc.perform(post("/databases/{id}/backup", targetId).with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/databases"))
                .andExpect(flash().attribute("error", "That target no longer exists"));
    }

    @Test
    void refusingToRemoveATargetWithBackupsIsReportedNotThrown() throws Exception {
        UUID targetId = UUID.randomUUID();
        org.mockito.Mockito.doThrow(new TargetInUseException("production"))
                .when(service).delete(targetId);

        mockMvc.perform(post("/databases/{id}/delete", targetId).with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/databases"))
                .andExpect(flash().attribute("error",
                        org.hamcrest.Matchers.containsString("still has backups")));
    }

    @Test
    void deletesATarget() throws Exception {
        UUID id = UUID.randomUUID();

        mockMvc.perform(post("/databases/{id}/delete", id).with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/databases"));

        verify(service).delete(id);
    }

    private static DatabaseTarget target(String name) {
        return target(name, null);
    }

    private static DatabaseTarget tested(ConnectionCheck check) {
        return target("production", check);
    }

    private static DatabaseTarget target(String name, ConnectionCheck check) {
        return DatabaseTarget.builder()
                .id(UUID.randomUUID())
                .name(name)
                .host("127.0.0.1")
                .port(3306)
                .databaseName("shop")
                .username("backup")
                .passwordCiphertext("Y2lwaGVydGV4dA==")
                .createdAt(Instant.parse("2026-09-09T10:15:30Z"))
                .lastConnectionCheck(check)
                .build();
    }
}
