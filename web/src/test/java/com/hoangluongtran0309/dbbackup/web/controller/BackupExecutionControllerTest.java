package com.hoangluongtran0309.dbbackup.web.controller;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.hoangluongtran0309.dbbackup.application.backup.BackupArtifactService;
import com.hoangluongtran0309.dbbackup.application.target.ManageDatabaseTargetService;
import com.hoangluongtran0309.dbbackup.core.model.BackupExecution;
import com.hoangluongtran0309.dbbackup.core.model.DatabaseTarget;
import com.hoangluongtran0309.dbbackup.core.port.BackupExecutionRepository;
import com.hoangluongtran0309.dbbackup.core.port.HistoryPage;
import com.hoangluongtran0309.dbbackup.web.security.SecurityConfig;

@WebMvcTest(BackupExecutionController.class)
// The console's real rules: signed in, and every POST carries a CSRF token.
@Import(SecurityConfig.class)
@WithMockUser
class BackupExecutionControllerTest {

    private static final Instant STARTED = Instant.parse("2026-09-09T10:00:00Z");
    private static final String SHA256 = "9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08";
    private static final UUID TARGET_ID = UUID.randomUUID();

    /** How the artifact at /backups/shop_20260909_100000.sql.gz is rendered. */
    private static final String ARTIFACT_FILE_NAME = "<span class=\"mono break\">shop_20260909_100000.sql.gz</span>";
    private static final String ARTIFACT_DIRECTORY = "in <span class=\"mono break\">/backups</span>";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private BackupExecutionRepository executions;

    @MockitoBean
    private ManageDatabaseTargetService targets;

    @MockitoBean
    private BackupArtifactService artifacts;

    @Test
    void showsAnEmptyStateWhenNothingHasBeenBackedUp() throws Exception {
        when(executions.findNewestFirst(1, 50)).thenReturn(new HistoryPage<>(List.of(), 1, false));
        when(targets.listAll()).thenReturn(List.of());

        mockMvc.perform(get("/executions"))
                .andExpect(status().isOk())
                .andExpect(view().name("execution/list"))
                .andExpect(content().string(containsString("No backups yet")));
    }

    @Test
    void listsExecutionsWithTheirTargetName() throws Exception {
        when(executions.findNewestFirst(1, 50)).thenReturn(new HistoryPage<>(List.of(succeeded()), 1, false));
        when(targets.listAll()).thenReturn(List.of(target("production")));

        mockMvc.perform(get("/executions"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("production")))
                .andExpect(content().string(containsString("Succeeded")))
                .andExpect(content().string(containsString("2026-09-09 10:00:00 UTC")));
    }

    /**
     * The regression that only looking at the page revealed. {@code th:replace}
     * swaps out the {@code <td>} itself, so the badge ended up loose in the
     * {@code <tr>}: the browser hoisted it above the table and every column
     * after it shifted left by one. Asserting the text was on the page — which
     * the other tests do — passed the whole time.
     */
    @Test
    void theStatusBadgeStaysInsideItsOwnTableCell() throws Exception {
        when(executions.findNewestFirst(1, 50)).thenReturn(new HistoryPage<>(List.of(succeeded()), 1, false));
        when(targets.listAll()).thenReturn(List.of(target("production")));

        mockMvc.perform(get("/executions"))
                .andExpect(content().string(containsString(
                        "<td data-label=\"Status\"><span class=\"badge badge-success\">Succeeded</span></td>")))
                // Which is also what keeps the size in the size column.
                .andExpect(content().string(containsString("<td data-label=\"Size\" class=\"mono\">8192 B</td>")));
    }

    /** History outlives the target it refers to; it must still render. */
    @Test
    void showsRemovedWhenTheTargetIsGone() throws Exception {
        when(executions.findNewestFirst(1, 50)).thenReturn(new HistoryPage<>(List.of(succeeded()), 1, false));
        when(targets.listAll()).thenReturn(List.of());

        mockMvc.perform(get("/executions"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("(removed)")));
    }

    /**
     * The file name leads; the directory is a secondary line under it. And the
     * fragment is the grid's own {@code <dd>}, not something nested in one.
     */
    @Test
    void detailShowsTheArtifactFileNameItsDirectoryAndSize() throws Exception {
        BackupExecution execution = succeeded();
        when(executions.findById(execution.getId())).thenReturn(Optional.of(execution));
        when(targets.listAll()).thenReturn(List.of(target("production")));

        mockMvc.perform(get("/executions/{id}", execution.getId()))
                .andExpect(status().isOk())
                .andExpect(view().name("execution/detail"))
                .andExpect(content().string(containsString(ARTIFACT_FILE_NAME)))
                .andExpect(content().string(containsString(ARTIFACT_DIRECTORY)))
                .andExpect(content().string(not(containsString("/backups/shop_20260909_100000.sql.gz"))))
                .andExpect(content().string(matchesPattern("(?s).*<dt>Artifact</dt>\\s*<dd>\\s*<div class=\"cell-stack\">.*")))
                .andExpect(content().string(containsString("8192 bytes")))
                // Finished: nothing for the page to follow (ADR-010).
                .andExpect(content().string(containsString("data-live-active=\"false\"")));
    }

    @Test
    void detailOfARunningBackupSaysSoAndHidesTheArtifactFields() throws Exception {
        BackupExecution execution = BackupExecution.started(UUID.randomUUID(), TARGET_ID, STARTED);
        when(executions.findById(execution.getId())).thenReturn(Optional.of(execution));
        when(targets.listAll()).thenReturn(List.of(target("production")));

        mockMvc.perform(get("/executions/{id}", execution.getId()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("still running")))
                .andExpect(content().string(containsString("Running")))
                .andExpect(content().string(not(containsString("Why it failed"))))
                // The page follows the run until it finishes (ADR-010), and
                // says what to announce when it does.
                .andExpect(content().string(containsString("data-live-active=\"true\"")))
                .andExpect(content().string(containsString("data-live-announce=\"Backup running\"")));
    }

    /** mysqldump's own output, shown in full rather than summarised. */
    @Test
    void detailOfAFailedBackupShowsTheToolsOwnOutput() throws Exception {
        BackupExecution execution = BackupExecution.started(UUID.randomUUID(), TARGET_ID, STARTED)
                .failed("mysqldump exited with 2: Access denied for user 'backup'@'%'", STARTED.plusSeconds(4));
        when(executions.findById(execution.getId())).thenReturn(Optional.of(execution));
        when(targets.listAll()).thenReturn(List.of(target("production")));

        mockMvc.perform(get("/executions/{id}", execution.getId()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Why it failed")))
                .andExpect(content().string(containsString("Access denied for user")))
                .andExpect(content().string(containsString("Failed")));
    }

    // --- download -----------------------------------------------------------

    @Test
    void downloadsTheArchiveAsAnAttachment() throws Exception {
        BackupExecution execution = succeeded();
        when(artifacts.download(execution.getId())).thenReturn(
                new BackupArtifactService.ArtifactDownload(
                        "shop_20260909_100000.sql.gz",
                        new ByteArrayInputStream("gzipped".getBytes(StandardCharsets.UTF_8))));

        mockMvc.perform(get("/executions/{id}/download", execution.getId()))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition",
                        org.hamcrest.Matchers.containsString("attachment")))
                .andExpect(header().string("Content-Disposition",
                        org.hamcrest.Matchers.containsString("shop_20260909_100000.sql.gz")))
                .andExpect(content().string("gzipped"));
    }

    /** Anything a URL points at can be gone by the time the URL is followed. */
    @Test
    void aMissingArtifactBecomesAMessageNotAServerError() throws Exception {
        UUID id = UUID.randomUUID();
        when(artifacts.download(id))
                .thenThrow(new NoSuchElementException("The artifact for this backup is no longer on disk"));

        mockMvc.perform(get("/executions/{id}/download", id))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/executions"))
                .andExpect(flash().attribute("error",
                        org.hamcrest.Matchers.containsString("no longer on disk")));
    }

    // --- delete -------------------------------------------------------------

    @Test
    void theDeletePageSaysWhatWillGoWithTheBackup() throws Exception {
        BackupExecution execution = succeeded();
        when(artifacts.previewDeletion(execution.getId())).thenReturn(
                new BackupArtifactService.DeletionPreview(execution, true, 2L));
        when(targets.listAll()).thenReturn(List.of(target("production")));

        mockMvc.perform(get("/executions/{id}/delete", execution.getId()))
                .andExpect(status().isOk())
                .andExpect(view().name("execution/delete"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("There is no undo")))
                .andExpect(content().string(containsString("<span>2</span>")))
                .andExpect(content().string(containsString("restore records refer to this backup and are deleted with it")))
                .andExpect(content().string(containsString(ARTIFACT_FILE_NAME)))
                .andExpect(content().string(containsString(ARTIFACT_DIRECTORY)));
    }

    @Test
    void theDeletePageSaysWhenTheFileIsAlreadyGone() throws Exception {
        BackupExecution execution = succeeded();
        when(artifacts.previewDeletion(execution.getId())).thenReturn(
                new BackupArtifactService.DeletionPreview(execution, false, 0L));
        when(targets.listAll()).thenReturn(List.of(target("production")));

        mockMvc.perform(get("/executions/{id}/delete", execution.getId()))
                .andExpect(content().string(
                        org.hamcrest.Matchers.containsString("already missing from disk")))
                // Nothing left on disk to delete, so the page must not say it will.
                .andExpect(content().string(not(containsString("deleted from disk"))))
                .andExpect(content().string(
                        org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("restore record"))));
    }

    @Test
    void theDeletePageCountsASingleRestoreInTheSingular() throws Exception {
        BackupExecution execution = succeeded();
        when(artifacts.previewDeletion(execution.getId())).thenReturn(
                new BackupArtifactService.DeletionPreview(execution, true, 1L));
        when(targets.listAll()).thenReturn(List.of(target("production")));

        mockMvc.perform(get("/executions/{id}/delete", execution.getId()))
                .andExpect(content().string(containsString("1 restore record refers to this backup and is deleted with it")))
                .andExpect(content().string(not(containsString("restore records"))));
    }

    /** A failed backup never had a file; nothing is "missing". */
    @Test
    void theDeletePageOfAFailedBackupSaysThereWasNeverAFile() throws Exception {
        BackupExecution execution = failed();
        when(artifacts.previewDeletion(execution.getId())).thenReturn(
                new BackupArtifactService.DeletionPreview(execution, false, 0L));
        when(targets.listAll()).thenReturn(List.of(target("production")));

        mockMvc.perform(get("/executions/{id}/delete", execution.getId()))
                .andExpect(content().string(containsString("There is no undo")))
                .andExpect(content().string(containsString("never produced a file")))
                .andExpect(content().string(not(containsString("deleted from disk"))))
                .andExpect(content().string(not(containsString("already missing from disk"))));
    }

    @Test
    void deletesABackupAndSaysSo() throws Exception {
        UUID id = UUID.randomUUID();
        when(artifacts.delete(id)).thenReturn(true);

        mockMvc.perform(post("/executions/{id}/delete", id).with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/executions"))
                .andExpect(flash().attribute("message", "Backup deleted, along with its artifact"));

        verify(artifacts).delete(id);
    }

    @Test
    void deletingABackupThatHadNoArtifactSaysOnlyTheRecordWent() throws Exception {
        UUID id = UUID.randomUUID();
        when(artifacts.delete(id)).thenReturn(false);

        mockMvc.perform(post("/executions/{id}/delete", id).with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attribute("message", "Backup record deleted — it had no artifact"));
    }

    @Test
    void refusingToDeleteARunningBackupIsReportedNotThrown() throws Exception {
        UUID id = UUID.randomUUID();
        org.mockito.Mockito.doThrow(new IllegalStateException(
                        "This backup is still running. Wait for it to finish before deleting it."))
                .when(artifacts).delete(id);

        mockMvc.perform(post("/executions/{id}/delete", id).with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attribute("error",
                        org.hamcrest.Matchers.containsString("still running")));
    }

    @Test
    void deletingABackupRemovedInAnotherTabDoesNotBlowUp() throws Exception {
        UUID id = UUID.randomUUID();
        org.mockito.Mockito.doThrow(new NoSuchElementException("gone")).when(artifacts).delete(id);

        mockMvc.perform(post("/executions/{id}/delete", id).with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attribute("error", "That backup no longer exists"));
    }

    // --- delete several ------------------------------------------------------

    /** A running backup is still writing its file, so it has no box to tick. */
    @Test
    void theListOffersABoxForEveryBackupButARunningOne() throws Exception {
        BackupExecution done = succeeded();
        BackupExecution running = BackupExecution.started(UUID.randomUUID(), TARGET_ID, STARTED.plusSeconds(60));
        when(executions.findNewestFirst(1, 50)).thenReturn(new HistoryPage<>(List.of(running, done), 1, false));
        when(targets.listAll()).thenReturn(List.of(target("production")));

        mockMvc.perform(get("/executions"))
                .andExpect(content().string(containsString("action=\"/executions/delete\"")))
                .andExpect(content().string(containsString("form=\"delete-selected\"")))
                .andExpect(content().string(containsString("name=\"id\" value=\"" + done.getId() + "\"")))
                .andExpect(content().string(not(containsString("value=\"" + running.getId() + "\""))));
    }

    @Test
    void theDeleteManyPageTotalsWhatGoes() throws Exception {
        BackupExecution first = succeeded();
        BackupExecution second = failed();
        when(artifacts.previewDeletions(List.of(first.getId(), second.getId()))).thenReturn(
                new BackupArtifactService.BulkDeletionPreview(List.of(first, second), 1, 8192L, 2L, null));
        when(targets.listAll()).thenReturn(List.of(target("production")));

        mockMvc.perform(get("/executions/delete").param("id", first.getId().toString(), second.getId().toString()))
                .andExpect(status().isOk())
                .andExpect(view().name("execution/delete-many"))
                .andExpect(content().string(containsString("Delete these 2 backups?")))
                .andExpect(content().string(containsString("1 artifact, <span>8192</span> bytes, is deleted from disk")))
                .andExpect(content().string(containsString("restore records refer to them and are deleted with them")))
                .andExpect(content().string(containsString("shop_20260909_100000.sql.gz")))
                // Carried to the POST, which is what deletes.
                .andExpect(content().string(containsString("name=\"id\" value=\"" + second.getId() + "\"")))
                .andExpect(content().string(containsString("Delete these 2 backups</button>")));
    }

    /** Said instead of the button: nothing is deleted while one of them is in use. */
    @Test
    void theDeleteManyPageSaysWhyItIsRefusedAndOffersNoButton() throws Exception {
        BackupExecution first = succeeded();
        when(artifacts.previewDeletions(List.of(first.getId()))).thenReturn(
                new BackupArtifactService.BulkDeletionPreview(List.of(first), 1, 8192L, 0L,
                        "This backup is being restored right now. Wait for the restore to finish before deleting it."));
        when(targets.listAll()).thenReturn(List.of(target("production")));

        mockMvc.perform(get("/executions/delete").param("id", first.getId().toString()))
                .andExpect(content().string(containsString("is being restored right now")))
                .andExpect(content().string(not(containsString("Delete this backup</button>"))));
    }

    @Test
    void tickingNothingIsSaidNotShownAsAnEmptyPage() throws Exception {
        mockMvc.perform(get("/executions/delete"))
                .andExpect(redirectedUrl("/executions"))
                .andExpect(flash().attribute("error", "Tick the backups to delete first"));
        verify(artifacts, never()).previewDeletions(any());
    }

    @Test
    void tickedBackupsThatAreAllGoneAreSaidToBeGone() throws Exception {
        UUID gone = UUID.randomUUID();
        when(artifacts.previewDeletions(List.of(gone))).thenReturn(
                new BackupArtifactService.BulkDeletionPreview(List.of(), 0, 0L, 0L, null));

        mockMvc.perform(get("/executions/delete").param("id", gone.toString()))
                .andExpect(redirectedUrl("/executions"))
                .andExpect(flash().attribute("error", "Those backups no longer exist"));
    }

    @Test
    void deletesSeveralAndSummarisesWhatWent() throws Exception {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        UUID third = UUID.randomUUID();
        when(artifacts.deleteAll(List.of(first, second, third)))
                .thenReturn(new BackupArtifactService.BulkDeletion(2, 1, 1));

        mockMvc.perform(post("/executions/delete").with(csrf())
                        .param("id", first.toString(), second.toString(), third.toString()))
                .andExpect(redirectedUrl("/executions"))
                .andExpect(flash().attribute("message",
                        "2 backups deleted, along with 1 artifact — 1 was already gone"));
    }

    @Test
    void deletingOneThroughTheListSaysSoInTheSingular() throws Exception {
        UUID id = UUID.randomUUID();
        when(artifacts.deleteAll(List.of(id))).thenReturn(new BackupArtifactService.BulkDeletion(1, 1, 0));

        mockMvc.perform(post("/executions/delete").with(csrf()).param("id", id.toString()))
                .andExpect(flash().attribute("message", "1 backup deleted, along with 1 artifact"));
    }

    @Test
    void aRefusedBatchIsReportedNotThrown() throws Exception {
        UUID id = UUID.randomUUID();
        when(artifacts.deleteAll(List.of(id))).thenThrow(new IllegalStateException(
                "One of these backups is still running. Wait for it to finish before deleting it."));

        mockMvc.perform(post("/executions/delete").with(csrf()).param("id", id.toString()))
                .andExpect(redirectedUrl("/executions"))
                .andExpect(flash().attribute("error",
                        "One of these backups is still running. Wait for it to finish before deleting it."));
    }

    @Test
    void deletingSeveralNeedsACsrfToken() throws Exception {
        mockMvc.perform(post("/executions/delete").with(csrf().useInvalidToken())
                        .param("id", UUID.randomUUID().toString()))
                .andExpect(status().isForbidden());
        verify(artifacts, never()).deleteAll(any());
    }

    @Test
    void aRunningBackupOffersNoDownloadOrRestoreLink() throws Exception {
        BackupExecution execution = BackupExecution.started(UUID.randomUUID(), TARGET_ID, STARTED);
        when(executions.findById(execution.getId())).thenReturn(Optional.of(execution));
        when(targets.listAll()).thenReturn(List.of(target("production")));

        mockMvc.perform(get("/executions/{id}", execution.getId()))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("/download"))))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("/delete"))));
        verify(artifacts, never()).download(any());
    }

    // --- paging ---------------------------------------------------------------

    @Test
    void theListIsReadAPageAtATimeAndOffersOlderWhenThereIsMore() throws Exception {
        when(executions.findNewestFirst(1, 50)).thenReturn(new HistoryPage<>(List.of(succeeded()), 1, true));
        when(targets.listAll()).thenReturn(List.of(target("production")));

        mockMvc.perform(get("/executions"))
                .andExpect(content().string(containsString("href=\"/executions?page=2\"")))
                .andExpect(content().string(containsString("Older")))
                .andExpect(content().string(not(containsString("Newer"))));
    }

    @Test
    void aLaterPageOffersNewerAndOlder() throws Exception {
        when(executions.findNewestFirst(2, 50)).thenReturn(new HistoryPage<>(List.of(succeeded()), 2, true));
        when(targets.listAll()).thenReturn(List.of(target("production")));

        mockMvc.perform(get("/executions").param("page", "2"))
                .andExpect(content().string(containsString("href=\"/executions?page=1\"")))
                .andExpect(content().string(containsString("href=\"/executions?page=3\"")))
                .andExpect(content().string(containsString("Page 2")));
    }

    /** Nothing to page through: no pager at all. */
    @Test
    void aSinglePageHasNoPager() throws Exception {
        when(executions.findNewestFirst(1, 50)).thenReturn(new HistoryPage<>(List.of(succeeded()), 1, false));
        when(targets.listAll()).thenReturn(List.of(target("production")));

        mockMvc.perform(get("/executions"))
                .andExpect(content().string(not(containsString("class=\"pager\""))));
    }

    @Test
    void aPageBelowOneIsTheFirstPage() throws Exception {
        when(executions.findNewestFirst(1, 50)).thenReturn(new HistoryPage<>(List.of(), 1, false));
        when(targets.listAll()).thenReturn(List.of());

        mockMvc.perform(get("/executions").param("page", "-3"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("No backups yet")));
    }

    /** Past the end is not "no backups yet": it says so, and offers the way back. */
    @Test
    void aPagePastTheEndSaysSoAndLinksBack() throws Exception {
        when(executions.findNewestFirst(9, 50)).thenReturn(new HistoryPage<>(List.of(), 9, false));
        when(targets.listAll()).thenReturn(List.of());

        mockMvc.perform(get("/executions").param("page", "9"))
                .andExpect(content().string(containsString("Nothing this far back")))
                .andExpect(content().string(not(containsString("No backups yet"))))
                .andExpect(content().string(containsString("href=\"/executions?page=8\"")));
    }

    // --- checksums ------------------------------------------------------------

    @Test
    void detailShowsTheChecksumAndOffersToVerify() throws Exception {
        BackupExecution backup = succeeded();
        when(executions.findById(backup.getId())).thenReturn(Optional.of(backup));
        when(targets.listAll()).thenReturn(List.of(target("production")));
        when(artifacts.isOnDisk(backup)).thenReturn(true);

        mockMvc.perform(get("/executions/{id}", backup.getId()))
                .andExpect(content().string(containsString(SHA256)))
                .andExpect(content().string(containsString(
                        "action=\"/executions/" + backup.getId() + "/verify\"")))
                .andExpect(content().string(not(containsString("no longer on disk"))));
    }

    @Test
    void aBackupFromBeforeChecksumsSaysNoneWasRecorded() throws Exception {
        BackupExecution legacy = BackupExecution.builder()
                .id(UUID.randomUUID()).targetId(TARGET_ID)
                .status(com.hoangluongtran0309.dbbackup.core.model.ExecutionStatus.SUCCEEDED)
                .startedAt(STARTED).finishedAt(STARTED.plusSeconds(90))
                .artifactPath("/backups/shop_20260909_100000.sql.gz").sizeBytes(8192L).build();
        when(executions.findById(legacy.getId())).thenReturn(Optional.of(legacy));
        when(targets.listAll()).thenReturn(List.of(target("production")));
        when(artifacts.isOnDisk(legacy)).thenReturn(true);

        mockMvc.perform(get("/executions/{id}", legacy.getId()))
                .andExpect(content().string(containsString("Not recorded")));
    }

    /** Nothing that needs the file is offered once it is gone; deleting the record still is. */
    @Test
    void aBackupWhoseFileIsGoneSaysSoAndOffersOnlyDeletion() throws Exception {
        BackupExecution backup = succeeded();
        when(executions.findById(backup.getId())).thenReturn(Optional.of(backup));
        when(targets.listAll()).thenReturn(List.of(target("production")));
        when(artifacts.isOnDisk(backup)).thenReturn(false);

        mockMvc.perform(get("/executions/{id}", backup.getId()))
                .andExpect(content().string(containsString("no longer on disk")))
                .andExpect(content().string(not(containsString("/verify"))))
                .andExpect(content().string(not(containsString("/download"))))
                .andExpect(content().string(not(containsString("/restores/new"))))
                .andExpect(content().string(containsString("/executions/" + backup.getId() + "/delete")));
    }

    @Test
    void verifyingAnIntactArtifactSaysSo() throws Exception {
        UUID id = UUID.randomUUID();
        when(artifacts.verify(id)).thenReturn(new BackupArtifactService.Verification(
                BackupArtifactService.Integrity.INTACT, SHA256, SHA256));

        mockMvc.perform(post("/executions/{id}/verify", id).with(csrf()))
                .andExpect(redirectedUrl("/executions/" + id))
                .andExpect(flash().attribute("message", containsString("matches the checksum")));
    }

    @Test
    void verifyingAChangedArtifactShowsBothChecksumsAsAnError() throws Exception {
        UUID id = UUID.randomUUID();
        String now = "60303ae22b998861bce3b28f33eec1be758a213c86c93c076dbe9f558c11c752";
        when(artifacts.verify(id)).thenReturn(new BackupArtifactService.Verification(
                BackupArtifactService.Integrity.MISMATCH, SHA256, now));

        mockMvc.perform(post("/executions/{id}/verify", id).with(csrf()))
                .andExpect(redirectedUrl("/executions/" + id))
                .andExpect(flash().attribute("error", containsString(SHA256)))
                .andExpect(flash().attribute("error", containsString(now)))
                .andExpect(flash().attribute("error", containsString("will be refused")));
    }

    @Test
    void verifyingAMissingArtifactSaysItIsGone() throws Exception {
        UUID id = UUID.randomUUID();
        when(artifacts.verify(id)).thenReturn(new BackupArtifactService.Verification(
                BackupArtifactService.Integrity.MISSING, SHA256, null));

        mockMvc.perform(post("/executions/{id}/verify", id).with(csrf()))
                .andExpect(flash().attribute("error", "The artifact is no longer on disk"));
    }

    @Test
    void verifyingABackupFromBeforeChecksumsShowsTodaysValue() throws Exception {
        UUID id = UUID.randomUUID();
        when(artifacts.verify(id)).thenReturn(new BackupArtifactService.Verification(
                BackupArtifactService.Integrity.NOT_RECORDED, null, SHA256));

        mockMvc.perform(post("/executions/{id}/verify", id).with(csrf()))
                .andExpect(flash().attribute("message", containsString("predates checksums")))
                .andExpect(flash().attribute("message", containsString(SHA256)));
    }

    private static BackupExecution succeeded() {
        return BackupExecution.started(UUID.randomUUID(), TARGET_ID, STARTED)
                .succeeded("/backups/shop_20260909_100000.sql.gz", 8192L, SHA256, STARTED.plusSeconds(90));
    }

    private static BackupExecution failed() {
        return BackupExecution.started(UUID.randomUUID(), TARGET_ID, STARTED)
                .failed("mysqldump exited with 2", STARTED.plusSeconds(3));
    }

    private static DatabaseTarget target(String name) {
        return DatabaseTarget.builder()
                .id(TARGET_ID).name(name).host("127.0.0.1").port(3306)
                .databaseName("shop").username("backup")
                .passwordCiphertext("Y2lwaGVydGV4dA==").createdAt(STARTED)
                .build();
    }
}
