package com.hoangluongtran0309.dbbackup.web.controller;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.hoangluongtran0309.dbbackup.application.restore.RestoreBackupService;
import com.hoangluongtran0309.dbbackup.application.target.ManageDatabaseTargetService;
import com.hoangluongtran0309.dbbackup.core.exception.RestoreFailedException;
import com.hoangluongtran0309.dbbackup.core.model.BackupExecution;
import com.hoangluongtran0309.dbbackup.core.model.DatabaseTarget;
import com.hoangluongtran0309.dbbackup.core.model.RestoreExecution;
import com.hoangluongtran0309.dbbackup.core.port.BackupExecutionRepository;
import com.hoangluongtran0309.dbbackup.core.port.RestoreExecutionRepository;
import com.hoangluongtran0309.dbbackup.web.security.SecurityConfig;

@WebMvcTest(RestoreController.class)
// The console's real rules: signed in, and every POST carries a CSRF token.
@Import(SecurityConfig.class)
@WithMockUser
class RestoreControllerTest {

    private static final Instant STARTED = Instant.parse("2026-09-09T10:00:00Z");
    private static final String SHA256 = "9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08";
    private static final UUID TARGET_ID = UUID.randomUUID();
    private static final UUID BACKUP_ID = UUID.randomUUID();
    private static final UUID DRILL_ID = UUID.randomUUID();

    @Autowired private MockMvc mockMvc;

    @MockitoBean private RestoreBackupService restoreService;
    @MockitoBean private RestoreExecutionRepository restores;
    @MockitoBean private BackupExecutionRepository backups;
    @MockitoBean private ManageDatabaseTargetService targets;

    @Test
    void theConfirmationPageNamesTheSchemaAboutToBeOverwritten() throws Exception {
        givenBackupAndTarget();

        mockMvc.perform(get("/restores/new").param("backup", BACKUP_ID.toString()))
                .andExpect(status().isOk())
                .andExpect(view().name("restore/confirm"))
                .andExpect(content().string(containsString("This overwrites live data")))
                .andExpect(content().string(containsString("127.0.0.1:3306/shop")))
                .andExpect(content().string(containsString("Type <strong>production</strong>")))
                // Which file: its name, then the directory it is in.
                .andExpect(content().string(containsString(
                        "<span class=\"mono break\">shop_20260909_100000.sql.gz</span>")))
                .andExpect(content().string(containsString("in <span class=\"mono break\">/backups</span>")));
    }

    /** The caveat people assume wrongly, so the page has to say it. */
    @Test
    void theConfirmationPageSaysWhatRestoreDoesNotDo() throws Exception {
        givenBackupAndTarget();

        mockMvc.perform(get("/restores/new").param("backup", BACKUP_ID.toString()))
                .andExpect(content().string(containsString("are left untouched")))
                .andExpect(content().string(containsString("does not reset it")));
    }

    @Test
    void startsTheRestoreWhenTheTargetNameIsTypedExactly() throws Exception {
        givenBackupAndTarget();
        UUID restoreId = UUID.randomUUID();
        when(restoreService.start(BACKUP_ID, TARGET_ID)).thenReturn(restoreId);

        mockMvc.perform(post("/restores").with(csrf())
                        .param("backup", BACKUP_ID.toString())
                        .param("confirmation", "production"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/restores/" + restoreId));
    }

    @Test
    void acceptsSurroundingWhitespaceInTheConfirmation() throws Exception {
        givenBackupAndTarget();
        when(restoreService.start(BACKUP_ID, TARGET_ID)).thenReturn(UUID.randomUUID());

        mockMvc.perform(post("/restores").with(csrf())
                        .param("backup", BACKUP_ID.toString())
                        .param("confirmation", "  production  "))
                .andExpect(status().is3xxRedirection());

        verify(restoreService).start(BACKUP_ID, TARGET_ID);
    }

    @Test
    void refusesAConfirmationThatDoesNotMatchTheTargetName() throws Exception {
        givenBackupAndTarget();

        mockMvc.perform(post("/restores").with(csrf())
                        .param("backup", BACKUP_ID.toString())
                        .param("confirmation", "Production"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/restores/new?backup=" + BACKUP_ID))
                .andExpect(flash().attribute("error", containsString("Type the target's name exactly")));

        verify(restoreService, never()).start(any(), any());
    }

    @Test
    void refusesAnEmptyConfirmation() throws Exception {
        givenBackupAndTarget();

        mockMvc.perform(post("/restores").with(csrf()).param("backup", BACKUP_ID.toString()))
                .andExpect(status().is3xxRedirection());

        verify(restoreService, never()).start(any(), any());
    }

    @Test
    void reportsWhenTheBackupCannotBeRestored() throws Exception {
        givenBackupAndTarget();
        when(restoreService.start(BACKUP_ID, TARGET_ID))
                .thenThrow(new RestoreFailedException("That backup is FAILED, so there is nothing to restore"));

        mockMvc.perform(post("/restores").with(csrf())
                        .param("backup", BACKUP_ID.toString())
                        .param("confirmation", "production"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/executions/" + BACKUP_ID))
                .andExpect(flash().attribute("error", containsString("nothing to restore")));
    }

    // --- into another target (ADR-014) --------------------------------------

    @Test
    void theConfirmationPageOffersEveryTargetAndPreselectsTheSource() throws Exception {
        givenBackupAndTargets();

        mockMvc.perform(get("/restores/new").param("backup", BACKUP_ID.toString()))
                .andExpect(content().string(containsString("data-autosubmit")))
                .andExpect(content().string(containsString("production — 127.0.0.1:3306/shop (where it was taken)")))
                .andExpect(content().string(containsString("drill — scratch:3306/shop_drill")))
                .andExpect(content().string(containsString("value=\"" + TARGET_ID + "\" selected")))
                .andExpect(content().string(containsString("name=\"target\" value=\"" + TARGET_ID + "\"")));
    }

    /** Everything that names the overwritten schema follows the choice. */
    @Test
    void choosingAnotherTargetMakesThePageAboutThatTarget() throws Exception {
        givenBackupAndTargets();

        mockMvc.perform(get("/restores/new")
                        .param("backup", BACKUP_ID.toString())
                        .param("target", DRILL_ID.toString()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("recreated in\n      <code>scratch:3306/shop_drill</code>")))
                .andExpect(content().string(containsString("Type <strong>drill</strong> to confirm")))
                .andExpect(content().string(containsString("which is not touched")))
                .andExpect(content().string(containsString("name=\"target\" value=\"" + DRILL_ID + "\"")));
    }

    @Test
    void startsARestoreIntoTheChosenTargetOnItsOwnName() throws Exception {
        givenBackupAndTargets();
        UUID restoreId = UUID.randomUUID();
        when(restoreService.start(BACKUP_ID, DRILL_ID)).thenReturn(restoreId);

        mockMvc.perform(post("/restores").with(csrf())
                        .param("backup", BACKUP_ID.toString())
                        .param("target", DRILL_ID.toString())
                        .param("confirmation", "drill"))
                .andExpect(redirectedUrl("/restores/" + restoreId));
    }

    /** The source's name does not confirm overwriting a different target. */
    @Test
    void theSourcesNameDoesNotConfirmARestoreIntoAnotherTarget() throws Exception {
        givenBackupAndTargets();

        mockMvc.perform(post("/restores").with(csrf())
                        .param("backup", BACKUP_ID.toString())
                        .param("target", DRILL_ID.toString())
                        .param("confirmation", "production"))
                .andExpect(redirectedUrl("/restores/new?backup=" + BACKUP_ID + "&target=" + DRILL_ID))
                .andExpect(flash().attribute("error", containsString("'drill'")));

        verify(restoreService, never()).start(any(), any());
    }

    @Test
    void aChosenTargetRemovedInAnotherTabFallsBackToTheDefault() throws Exception {
        givenBackupAndTarget();

        mockMvc.perform(get("/restores/new")
                        .param("backup", BACKUP_ID.toString())
                        .param("target", UUID.randomUUID().toString()))
                .andExpect(redirectedUrl("/restores/new?backup=" + BACKUP_ID))
                .andExpect(flash().attribute("error", "That target no longer exists"));
    }

    @Test
    void theDetailSaysWhereTheDataWentAndWhereTheBackupCameFrom() throws Exception {
        RestoreExecution restore = RestoreExecution.started(UUID.randomUUID(), BACKUP_ID, DRILL_ID, STARTED)
                .succeeded(STARTED.plusSeconds(30));
        when(restores.findById(restore.getId())).thenReturn(Optional.of(restore));
        when(backups.findById(BACKUP_ID)).thenReturn(Optional.of(backup()));
        when(targets.listAll()).thenReturn(List.of(target(), drill()));

        mockMvc.perform(get("/restores/{id}", restore.getId()))
                .andExpect(content().string(containsString("<h1>drill</h1>")))
                .andExpect(content().string(org.hamcrest.Matchers.matchesPattern(
                        "(?s).*<dt>Taken from</dt>\\s*<dd>production</dd>.*")));
    }

    @Test
    void theListSaysIntoWhichTargetEachRestoreWent() throws Exception {
        RestoreExecution restore = RestoreExecution.started(UUID.randomUUID(), BACKUP_ID, DRILL_ID, STARTED);
        when(restores.findAllNewestFirst()).thenReturn(List.of(restore));
        when(backups.findAllNewestFirst()).thenReturn(List.of(backup()));
        when(targets.listAll()).thenReturn(List.of(target(), drill()));

        mockMvc.perform(get("/restores"))
                .andExpect(content().string(org.hamcrest.Matchers.matchesPattern(
                        "(?s).*<td data-label=\"Into\">drill</td>.*")))
                .andExpect(content().string(containsString("of production")));
    }

    @Test
    void handlesABackupDeletedInAnotherTab() throws Exception {
        when(backups.findById(BACKUP_ID)).thenReturn(Optional.empty());

        mockMvc.perform(get("/restores/new").param("backup", BACKUP_ID.toString()))
                .andExpect(redirectedUrl("/executions"))
                .andExpect(flash().attribute("error", "That backup no longer exists"));
    }

    @Test
    void showsAnEmptyStateWhenNothingHasBeenRestored() throws Exception {
        when(restores.findAllNewestFirst()).thenReturn(List.of());
        when(backups.findAllNewestFirst()).thenReturn(List.of());
        when(targets.listAll()).thenReturn(List.of());

        mockMvc.perform(get("/restores"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("No restores yet")));
    }

    @Test
    void detailShowsTheFailureVerbatim() throws Exception {
        RestoreExecution restore = RestoreExecution.started(UUID.randomUUID(), BACKUP_ID, TARGET_ID, STARTED)
                .failed("mysql exited with 1: ERROR 1142 at line 40", STARTED.plusSeconds(9));
        when(restores.findById(restore.getId())).thenReturn(Optional.of(restore));
        when(backups.findById(BACKUP_ID)).thenReturn(Optional.of(backup()));
        when(targets.listAll()).thenReturn(List.of(target()));

        mockMvc.perform(get("/restores/{id}", restore.getId()))
                .andExpect(status().isOk())
                .andExpect(view().name("restore/detail"))
                .andExpect(content().string(containsString("Why it failed")))
                .andExpect(content().string(containsString("ERROR 1142 at line 40")))
                .andExpect(content().string(containsString("production")))
                .andExpect(content().string(containsString("data-live-active=\"false\"")));
    }

    /** Followed until it finishes, like a running backup (ADR-010). */
    @Test
    void detailOfARunningRestoreIsFollowed() throws Exception {
        RestoreExecution restore = RestoreExecution.started(UUID.randomUUID(), BACKUP_ID, TARGET_ID, STARTED);
        when(restores.findById(restore.getId())).thenReturn(Optional.of(restore));
        when(backups.findById(BACKUP_ID)).thenReturn(Optional.of(backup()));
        when(targets.listAll()).thenReturn(List.of(target()));

        mockMvc.perform(get("/restores/{id}", restore.getId()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("still running")))
                .andExpect(content().string(containsString("data-live-active=\"true\"")))
                .andExpect(content().string(containsString("data-live-announce=\"Restore running\"")));
    }

    /** Back to the list it was reached from, not to the backup list. */
    @Test
    void aMissingRestoreSendsTheOperatorBackToTheRestoreList() throws Exception {
        UUID id = UUID.randomUUID();
        when(restores.findById(id)).thenReturn(Optional.empty());

        mockMvc.perform(get("/restores/{id}", id))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/restores"))
                .andExpect(flash().attribute("error", "That restore no longer exists"));
    }

    private void givenBackupAndTarget() {
        when(backups.findById(BACKUP_ID)).thenReturn(Optional.of(backup()));
        when(targets.listAll()).thenReturn(List.of(target()));
    }

    private void givenBackupAndTargets() {
        when(backups.findById(BACKUP_ID)).thenReturn(Optional.of(backup()));
        when(targets.listAll()).thenReturn(List.of(target(), drill()));
    }

    private static DatabaseTarget drill() {
        return DatabaseTarget.builder()
                .id(DRILL_ID).name("drill").host("scratch").port(3306)
                .databaseName("shop_drill").username("drill")
                .passwordCiphertext("ZHJpbGw=").createdAt(STARTED)
                .build();
    }

    private static BackupExecution backup() {
        return BackupExecution.started(BACKUP_ID, TARGET_ID, STARTED)
                .succeeded("/backups/shop_20260909_100000.sql.gz", 8192L, SHA256, STARTED.plusSeconds(60));
    }

    private static DatabaseTarget target() {
        return DatabaseTarget.builder()
                .id(TARGET_ID).name("production").host("127.0.0.1").port(3306)
                .databaseName("shop").username("backup")
                .passwordCiphertext("Y2lwaGVydGV4dA==").createdAt(STARTED)
                .build();
    }
}
