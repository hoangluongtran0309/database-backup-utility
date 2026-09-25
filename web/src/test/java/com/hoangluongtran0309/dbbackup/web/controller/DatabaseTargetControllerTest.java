package com.hoangluongtran0309.dbbackup.web.controller;

import static org.assertj.core.api.Assertions.assertThat;
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
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.mockito.ArgumentCaptor;

import com.hoangluongtran0309.dbbackup.application.backup.BackupArtifactService.BulkDeletionPreview;
import com.hoangluongtran0309.dbbackup.application.backup.RunBackupService;
import com.hoangluongtran0309.dbbackup.application.target.EditTargetCommand;
import com.hoangluongtran0309.dbbackup.application.target.ManageDatabaseTargetService;
import com.hoangluongtran0309.dbbackup.application.target.ManageDatabaseTargetService.TargetRemovalPreview;
import com.hoangluongtran0309.dbbackup.application.target.RegisterTargetCommand;
import com.hoangluongtran0309.dbbackup.application.target.TestTargetConnectionService;
import com.hoangluongtran0309.dbbackup.application.storage.ManageStorageProfileService;
import com.hoangluongtran0309.dbbackup.core.exception.DuplicateTargetNameException;
import com.hoangluongtran0309.dbbackup.core.exception.TargetInUseException;
import com.hoangluongtran0309.dbbackup.core.model.BackupExecution;
import com.hoangluongtran0309.dbbackup.core.model.ConnectionCheck;
import com.hoangluongtran0309.dbbackup.core.model.DatabaseEngine;
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

    @MockitoBean
    private ManageStorageProfileService storageProfiles;

    @BeforeEach
    void implementedEnginesAreAvailable() {
        when(storageProfiles.listAll()).thenReturn(List.of());
        when(service.availableEngines()).thenReturn(List.of(
                DatabaseEngine.MYSQL,
                DatabaseEngine.POSTGRESQL,
                DatabaseEngine.MONGODB,
                DatabaseEngine.SQLITE,
                DatabaseEngine.MARIADB,
                DatabaseEngine.SQLSERVER));
        when(service.supports(any())).thenReturn(true);
    }

    @Test
    void listsTargets() throws Exception {
        when(service.listAll()).thenReturn(List.of(target("production")));

        mockMvc.perform(get("/databases"))
                .andExpect(status().isOk())
                .andExpect(view().name("database/list"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("production")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("127.0.0.1:3306/shop")));
    }

    @Test
    void displaysAMariadbTargetWithItsPublicEngineName() throws Exception {
        when(service.listAll()).thenReturn(List.of(mariadbTarget("orders mariadb")));

        mockMvc.perform(get("/databases"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("orders mariadb")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString(">MariaDB</span>")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("mariadb.internal:3306/orders")));
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
        when(executions.findLatestPerTarget()).thenReturn(List.of(failed));
        when(executions.findLatestSucceededPerTarget()).thenReturn(List.of(good));

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
        when(executions.findLatestPerTarget()).thenReturn(List.of(
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
    void newFormOffersOnlyImplementedEnginesWithTheirDefaultPorts() throws Exception {
        mockMvc.perform(get("/databases/new"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.matchesPattern(
                        "(?s).*value=\"MYSQL\".*?data-default-port=\"3306\".*")))
                .andExpect(content().string(org.hamcrest.Matchers.matchesPattern(
                        "(?s).*value=\"POSTGRESQL\".*?data-default-port=\"5432\".*")))
                .andExpect(content().string(org.hamcrest.Matchers.matchesPattern(
                        "(?s).*value=\"MONGODB\".*?data-default-port=\"27017\".*")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("value=\"SQLITE\"")))
                .andExpect(content().string(org.hamcrest.Matchers.matchesPattern(
                        "(?s).*value=\"MARIADB\".*?data-default-port=\"3306\".*")))
                .andExpect(content().string(org.hamcrest.Matchers.matchesPattern(
                        "(?s).*value=\"SQLSERVER\".*?data-default-port=\"1433\".*")));
    }

    @Test
    void registersASqlServerTargetWithTheSelectedEngine() throws Exception {
        when(service.register(any())).thenReturn(target("orders sql server"));

        mockMvc.perform(post("/databases").with(csrf())
                        .param("engine", "SQLSERVER")
                        .param("name", "orders sql server")
                        .param("host", "sql.internal")
                        .param("port", "1433")
                        .param("database", "orders")
                        .param("username", "backup")
                        .param("password", "Str0ng! password")
                        .param("verifyAfterBackup", "true"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/databases"));

        ArgumentCaptor<com.hoangluongtran0309.dbbackup.application.target.RegisterTargetCommand> command =
                ArgumentCaptor.forClass(
                        com.hoangluongtran0309.dbbackup.application.target.RegisterTargetCommand.class);
        verify(service).register(command.capture());
        assertThat(command.getValue().engine()).isEqualTo(DatabaseEngine.SQLSERVER);
        assertThat(command.getValue().port()).isEqualTo(1433);
        assertThat(command.getValue().verifyAfterBackup()).isTrue();
    }

    @Test
    void registersAValidTargetAndRedirects() throws Exception {
        when(service.register(any())).thenReturn(target("production"));

        mockMvc.perform(post("/databases").with(csrf())
                        .param("engine", "MYSQL")
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
    void registersAutomaticRestoreVerificationForASupportedEngine() throws Exception {
        when(service.register(any())).thenReturn(target("production"));

        mockMvc.perform(post("/databases").with(csrf())
                        .param("engine", "MYSQL")
                        .param("name", "production")
                        .param("host", "127.0.0.1")
                        .param("port", "3306")
                        .param("database", "shop")
                        .param("username", "backup")
                        .param("password", "s3cr3t")
                        .param("verifyAfterBackup", "true"))
                .andExpect(status().is3xxRedirection());

        ArgumentCaptor<RegisterTargetCommand> command = ArgumentCaptor.forClass(RegisterTargetCommand.class);
        verify(service).register(command.capture());
        assertThat(command.getValue().verifyAfterBackup()).isTrue();
    }

    @Test
    void registersAPostgresqlTargetWithTheSelectedEngine() throws Exception {
        when(service.register(any())).thenReturn(target("analytics"));

        mockMvc.perform(post("/databases").with(csrf())
                        .param("engine", "POSTGRESQL")
                        .param("name", "analytics")
                        .param("host", "postgres.internal")
                        .param("port", "5432")
                        .param("database", "warehouse")
                        .param("username", "backup")
                        .param("password", "s3cr3t"))
                .andExpect(status().is3xxRedirection());

        ArgumentCaptor<RegisterTargetCommand> command = ArgumentCaptor.forClass(RegisterTargetCommand.class);
        verify(service).register(command.capture());
        org.assertj.core.api.Assertions.assertThat(command.getValue().engine())
                .isEqualTo(DatabaseEngine.POSTGRESQL);
        org.assertj.core.api.Assertions.assertThat(command.getValue().port()).isEqualTo(5432);
    }

    @Test
    void registersAMariadbTargetWithTheSelectedEngine() throws Exception {
        when(service.register(any())).thenReturn(target("orders mariadb"));

        mockMvc.perform(post("/databases").with(csrf())
                        .param("engine", "MARIADB")
                        .param("name", "orders mariadb")
                        .param("host", "mariadb.internal")
                        .param("port", "3306")
                        .param("database", "orders")
                        .param("username", "backup")
                        .param("password", "s3cr3t"))
                .andExpect(status().is3xxRedirection());

        ArgumentCaptor<RegisterTargetCommand> command = ArgumentCaptor.forClass(RegisterTargetCommand.class);
        verify(service).register(command.capture());
        assertThat(command.getValue().engine()).isEqualTo(DatabaseEngine.MARIADB);
        assertThat(command.getValue().port()).isEqualTo(3306);
    }

    @Test
    void registersAMongodbTargetWithItsAuthenticationDatabase() throws Exception {
        when(service.register(any())).thenReturn(target("documents"));

        mockMvc.perform(post("/databases").with(csrf())
                        .param("engine", "MONGODB")
                        .param("name", "documents")
                        .param("host", "mongo.internal")
                        .param("port", "27017")
                        .param("database", "shop")
                        .param("username", "backup")
                        .param("password", "s3cr3t")
                        .param("authenticationDatabase", "admin"))
                .andExpect(status().is3xxRedirection());

        ArgumentCaptor<RegisterTargetCommand> command = ArgumentCaptor.forClass(RegisterTargetCommand.class);
        verify(service).register(command.capture());
        org.assertj.core.api.Assertions.assertThat(command.getValue().engine())
                .isEqualTo(DatabaseEngine.MONGODB);
        org.assertj.core.api.Assertions.assertThat(command.getValue().authenticationDatabase())
                .isEqualTo("admin");
    }

    @Test
    void registersASqliteTargetWithOnlyItsRelativeFile() throws Exception {
        when(service.register(any())).thenReturn(sqliteTarget("local shop"));

        mockMvc.perform(post("/databases").with(csrf())
                        .param("engine", "SQLITE")
                        .param("name", "local shop")
                        .param("database", "apps/shop.db"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/databases"));

        ArgumentCaptor<RegisterTargetCommand> command = ArgumentCaptor.forClass(RegisterTargetCommand.class);
        verify(service).register(command.capture());
        assertThat(command.getValue().engine()).isEqualTo(DatabaseEngine.SQLITE);
        assertThat(command.getValue().database()).isEqualTo("apps/shop.db");
        assertThat(command.getValue().host()).isNull();
        assertThat(command.getValue().port()).isNull();
        assertThat(command.getValue().password()).isNull();
    }

    @Test
    void registersAnOracleTargetWithServiceSchemaAndDataPumpDirectory() throws Exception {
        when(service.register(any())).thenReturn(oracleTarget("orders oracle"));

        mockMvc.perform(post("/databases").with(csrf())
                        .param("engine", "ORACLE")
                        .param("name", "orders oracle")
                        .param("host", "oracle.internal")
                        .param("port", "1521")
                        .param("database", "FREEPDB1")
                        .param("username", "APP_OWNER")
                        .param("password", "s3cr3t")
                        .param("dataPumpDirectory", "DBBACKUP_PUMP_DIR"))
                .andExpect(status().is3xxRedirection());

        ArgumentCaptor<RegisterTargetCommand> command = ArgumentCaptor.forClass(RegisterTargetCommand.class);
        verify(service).register(command.capture());
        assertThat(command.getValue().engine()).isEqualTo(DatabaseEngine.ORACLE);
        assertThat(command.getValue().database()).isEqualTo("FREEPDB1");
        assertThat(command.getValue().dataPumpDirectory()).isEqualTo("DBBACKUP_PUMP_DIR");
    }

    @Test
    void oracleRegistrationRequiresADataPumpDirectory() throws Exception {
        mockMvc.perform(post("/databases").with(csrf())
                        .param("engine", "ORACLE")
                        .param("name", "orders oracle")
                        .param("host", "oracle.internal")
                        .param("port", "1521")
                        .param("database", "FREEPDB1")
                        .param("username", "APP_OWNER")
                        .param("password", "s3cr3t"))
                .andExpect(status().isOk())
                .andExpect(model().attributeHasFieldErrors("form", "dataPumpDirectory"));

        verify(service, never()).register(any());
    }

    @Test
    void existingOracleTargetIsVisibleButOperationsAreDisabledWithoutThePack() throws Exception {
        DatabaseTarget oracle = oracleTarget("orders oracle");
        when(service.listAll()).thenReturn(List.of(oracle));
        when(service.supports(DatabaseEngine.ORACLE)).thenReturn(false);

        mockMvc.perform(get("/databases"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("orders oracle")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Client unavailable")))
                .andExpect(content().string(org.hamcrest.Matchers.matchesPattern(
                        "(?s).*action=\"/databases/" + oracle.getId() + "/test\".*?<button[^>]*disabled.*")));
    }

    @Test
    void redisplaysTheFormWithTheTypedValuesWhenAFieldIsInvalid() throws Exception {
        mockMvc.perform(post("/databases").with(csrf())
                        .param("engine", "MYSQL")
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
                        .param("engine", "MYSQL")
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
                        .param("engine", "MYSQL")
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
                .andExpect(content().string(org.hamcrest.Matchers.containsString("readonly value=\"MySQL\"")))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("name=\"engine\""))))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("name=\"database\""))))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("Y2lwaGVydGV4dA=="))))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Save changes")));
    }

    @Test
    void mongodbEditFormShowsItsEditableAuthenticationDatabase() throws Exception {
        DatabaseTarget target = mongoTarget("documents");
        when(service.get(target.getId())).thenReturn(target);

        mockMvc.perform(get("/databases/{id}/edit", target.getId()))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("readonly value=\"MongoDB\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "name=\"authenticationDatabase\" value=\"admin\"")));
    }

    @Test
    void sqliteListAndEditFormShowAFileWithoutCredentialFields() throws Exception {
        DatabaseTarget target = sqliteTarget("local shop");
        when(service.listAll()).thenReturn(List.of(target));

        mockMvc.perform(get("/databases"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("apps/shop.db")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("below SQLITE_ROOT")));

        when(service.get(target.getId())).thenReturn(target);
        mockMvc.perform(get("/databases/{id}/edit", target.getId()))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("readonly value=\"SQLite\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("readonly value=\"apps/shop.db\"")))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("name=\"host\""))))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("name=\"password\""))));
    }

    @Test
    void sqliteEditAcceptsARenameWithoutNetworkFields() throws Exception {
        DatabaseTarget target = sqliteTarget("local shop");
        when(service.get(target.getId())).thenReturn(target);
        when(service.edit(eq(target.getId()), any())).thenReturn(sqliteTarget("renamed"));

        mockMvc.perform(post("/databases/{id}", target.getId()).with(csrf())
                        .param("name", "renamed"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/databases"));

        verify(service).edit(target.getId(), new EditTargetCommand("renamed", null, null, null, null, null));
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

    // --- remove -------------------------------------------------------------

    /** A target with backups goes to a page, not a one-click dialog; one without keeps the dialog. */
    @Test
    void offersThePageForATargetWithBackupsAndTheDialogForOneWithout() throws Exception {
        DatabaseTarget withBackups = target("production");
        DatabaseTarget empty = target("drill");
        when(service.listAll()).thenReturn(List.of(withBackups, empty));
        when(executions.findLatestPerTarget()).thenReturn(List.of(
                BackupExecution.started(UUID.randomUUID(), withBackups.getId(), BACKED_UP_AT)
                        .failed("boom", BACKED_UP_AT.plusSeconds(1))));

        mockMvc.perform(get("/databases"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "href=\"/databases/" + withBackups.getId() + "/delete\"")))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString(
                        "href=\"/databases/" + empty.getId() + "/delete\""))))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Remove target “drill”?")));
    }

    @Test
    void theRemovePageCountsWhatGoesAndAsksForTheName() throws Exception {
        DatabaseTarget target = target("production");
        BackupExecution backup = BackupExecution.started(UUID.randomUUID(), target.getId(), BACKED_UP_AT)
                .succeeded("/backups/shop.sql.gz", 8192, SHA256, BACKED_UP_AT.plusSeconds(5));
        when(service.previewRemoval(target.getId())).thenReturn(new TargetRemovalPreview(
                target,
                new BulkDeletionPreview(List.of(backup, backup), 2, 16384L, 1L, null),
                3L,
                0L,
                null));

        mockMvc.perform(get("/databases/{id}/delete", target.getId()))
                .andExpect(status().isOk())
                .andExpect(view().name("database/delete"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("All 2 of its backups go with it")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("16384</span> bytes in all")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("3</span> restore records")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("name=\"confirmation\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Remove it and its backups")));
    }

    @Test
    void theRemovePageOfATargetWithoutBackupsAsksOnlyForAClick() throws Exception {
        DatabaseTarget target = target("drill");
        when(service.previewRemoval(target.getId())).thenReturn(new TargetRemovalPreview(
                target, new BulkDeletionPreview(List.of(), 0, 0L, 0L, null), 0L, 0L, null));

        mockMvc.perform(get("/databases/{id}/delete", target.getId()))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("name=\"confirmation\""))))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Remove this target")));
    }

    /** Said instead of the button, so nobody types a name only to be told no. */
    @Test
    void theRemovePageSaysWhyItCannotGoAndOffersNoButton() throws Exception {
        DatabaseTarget target = target("production");
        when(service.previewRemoval(target.getId())).thenReturn(new TargetRemovalPreview(
                target, new BulkDeletionPreview(List.of(), 0, 0L, 0L, null), 0L, 0L,
                "A restore into this target is running. Wait for it to finish before removing the target."));

        mockMvc.perform(get("/databases/{id}/delete", target.getId()))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("A restore into this target is running")))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("Remove this target"))));
    }

    @Test
    void theRemovePageOfATargetDeletedInAnotherTabSaysSo() throws Exception {
        UUID id = UUID.randomUUID();
        when(service.previewRemoval(id)).thenThrow(new NoSuchElementException("gone"));

        mockMvc.perform(get("/databases/{id}/delete", id))
                .andExpect(redirectedUrl("/databases"))
                .andExpect(flash().attribute("error", "That target no longer exists"));
    }

    @Test
    void removingATargetWithBackupsUnnamedSendsItBackToThePage() throws Exception {
        DatabaseTarget target = target("production");
        when(service.get(target.getId())).thenReturn(target);
        org.mockito.Mockito.doThrow(new TargetInUseException("production"))
                .when(service).delete(target.getId(), false);

        mockMvc.perform(post("/databases/{id}/delete", target.getId()).with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/databases/" + target.getId() + "/delete"))
                .andExpect(flash().attribute("error",
                        org.hamcrest.Matchers.containsString("still has backups")));
    }

    @Test
    void aWrongNameIsNotAConfirmation() throws Exception {
        DatabaseTarget target = target("production");
        when(service.get(target.getId())).thenReturn(target);
        org.mockito.Mockito.doThrow(new TargetInUseException("production"))
                .when(service).delete(target.getId(), false);

        mockMvc.perform(post("/databases/{id}/delete", target.getId()).with(csrf()).param("confirmation", "Production"))
                .andExpect(redirectedUrl("/databases/" + target.getId() + "/delete"))
                .andExpect(flash().attribute("error",
                        "Type the target's name exactly — 'production' — to remove it with its backups"));
    }

    @Test
    void removesATargetWithItsBackupsWhenTheNameIsTyped() throws Exception {
        DatabaseTarget target = target("production");
        when(service.get(target.getId())).thenReturn(target);

        mockMvc.perform(post("/databases/{id}/delete", target.getId()).with(csrf()).param("confirmation", " production "))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/databases"))
                .andExpect(flash().attribute("message", "Target 'production' removed"));

        verify(service).delete(target.getId(), true);
    }

    @Test
    void aRefusalWhileSomethingIsRunningIsReportedNotThrown() throws Exception {
        DatabaseTarget target = target("production");
        when(service.get(target.getId())).thenReturn(target);
        org.mockito.Mockito.doThrow(new IllegalStateException("One of these backups is still running."))
                .when(service).delete(target.getId(), true);

        mockMvc.perform(post("/databases/{id}/delete", target.getId()).with(csrf()).param("confirmation", "production"))
                .andExpect(redirectedUrl("/databases/" + target.getId() + "/delete"))
                .andExpect(flash().attribute("error", "One of these backups is still running."));
    }

    @Test
    void removingATargetThatIsAlreadyGoneIsNotAnError() throws Exception {
        UUID id = UUID.randomUUID();
        when(service.get(id)).thenThrow(new NoSuchElementException("gone"));

        mockMvc.perform(post("/databases/{id}/delete", id).with(csrf()))
                .andExpect(redirectedUrl("/databases"))
                .andExpect(flash().attribute("message", "Target removed"));

        verify(service, never()).delete(any(), org.mockito.ArgumentMatchers.anyBoolean());
    }

    @Test
    void removingATargetNeedsACsrfToken() throws Exception {
        UUID id = UUID.randomUUID();

        mockMvc.perform(post("/databases/{id}/delete", id).with(csrf().useInvalidToken()))
                .andExpect(status().isForbidden());

        verify(service, never()).delete(any(), org.mockito.ArgumentMatchers.anyBoolean());
    }

    private static DatabaseTarget target(String name) {
        return target(name, null);
    }

    private static DatabaseTarget tested(ConnectionCheck check) {
        return target("production", check);
    }

    private static DatabaseTarget target(String name, ConnectionCheck check) {
        return DatabaseTarget.builder().engine(com.hoangluongtran0309.dbbackup.core.model.DatabaseEngine.MYSQL)
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

    private static DatabaseTarget mongoTarget(String name) {
        return DatabaseTarget.builder().engine(DatabaseEngine.MONGODB)
                .id(UUID.randomUUID())
                .name(name)
                .host("mongo.internal")
                .port(27017)
                .databaseName("shop")
                .username("backup")
                .authenticationDatabase("admin")
                .passwordCiphertext("Y2lwaGVydGV4dA==")
                .createdAt(Instant.parse("2026-09-09T10:15:30Z"))
                .build();
    }

    private static DatabaseTarget mariadbTarget(String name) {
        return DatabaseTarget.builder().engine(DatabaseEngine.MARIADB)
                .id(UUID.randomUUID())
                .name(name)
                .host("mariadb.internal")
                .port(3306)
                .databaseName("orders")
                .username("backup")
                .passwordCiphertext("Y2lwaGVydGV4dA==")
                .createdAt(Instant.parse("2026-09-09T10:15:30Z"))
                .build();
    }

    private static DatabaseTarget sqliteTarget(String name) {
        return DatabaseTarget.builder().engine(DatabaseEngine.SQLITE)
                .id(UUID.randomUUID())
                .name(name)
                .databaseName("apps/shop.db")
                .createdAt(Instant.parse("2026-09-09T10:15:30Z"))
                .build();
    }

    private static DatabaseTarget oracleTarget(String name) {
        return DatabaseTarget.builder().engine(DatabaseEngine.ORACLE)
                .id(UUID.randomUUID())
                .name(name)
                .host("oracle.internal")
                .port(1521)
                .databaseName("FREEPDB1")
                .username("APP_OWNER")
                .dataPumpDirectory("DBBACKUP_PUMP_DIR")
                .passwordCiphertext("Y2lwaGVydGV4dA==")
                .createdAt(Instant.parse("2026-09-09T10:15:30Z"))
                .build();
    }
}
