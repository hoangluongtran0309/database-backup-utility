package com.hoangluongtran0309.dbbackup.web.controller;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.hoangluongtran0309.dbbackup.application.backup.BackupArtifactService;
import com.hoangluongtran0309.dbbackup.application.target.ManageDatabaseTargetService;
import com.hoangluongtran0309.dbbackup.core.model.BackupExecution;
import com.hoangluongtran0309.dbbackup.core.model.DatabaseTarget;
import com.hoangluongtran0309.dbbackup.core.port.BackupExecutionRepository;

@WebMvcTest(BackupExecutionController.class)
class BackupExecutionControllerTest {

    private static final Instant STARTED = Instant.parse("2026-09-09T10:00:00Z");
    private static final UUID TARGET_ID = UUID.randomUUID();

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
        when(executions.findAllNewestFirst()).thenReturn(List.of());
        when(targets.listAll()).thenReturn(List.of());

        mockMvc.perform(get("/executions"))
                .andExpect(status().isOk())
                .andExpect(view().name("execution/list"))
                .andExpect(content().string(containsString("No backups yet")));
    }

    @Test
    void listsExecutionsWithTheirTargetName() throws Exception {
        when(executions.findAllNewestFirst()).thenReturn(List.of(succeeded()));
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
        when(executions.findAllNewestFirst()).thenReturn(List.of(succeeded()));
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
        when(executions.findAllNewestFirst()).thenReturn(List.of(succeeded()));
        when(targets.listAll()).thenReturn(List.of());

        mockMvc.perform(get("/executions"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("(removed)")));
    }

    @Test
    void detailShowsTheArtifactPathAndSize() throws Exception {
        BackupExecution execution = succeeded();
        when(executions.findById(execution.getId())).thenReturn(Optional.of(execution));
        when(targets.listAll()).thenReturn(List.of(target("production")));

        mockMvc.perform(get("/executions/{id}", execution.getId()))
                .andExpect(status().isOk())
                .andExpect(view().name("execution/detail"))
                .andExpect(content().string(containsString("/backups/shop_20260909_100000.sql.gz")))
                .andExpect(content().string(containsString("8192 bytes")));
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
                .andExpect(content().string(not(containsString("Why it failed"))));
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
                .andExpect(content().string(org.hamcrest.Matchers.containsString("restore record(s)")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("/backups/shop_20260909_100000.sql.gz")));
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
                .andExpect(content().string(
                        org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("restore record(s)"))));
    }

    @Test
    void deletesABackupAndSaysSo() throws Exception {
        UUID id = UUID.randomUUID();

        mockMvc.perform(post("/executions/{id}/delete", id))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/executions"))
                .andExpect(flash().attribute("message", "Backup deleted, along with its artifact"));

        verify(artifacts).delete(id);
    }

    @Test
    void refusingToDeleteARunningBackupIsReportedNotThrown() throws Exception {
        UUID id = UUID.randomUUID();
        org.mockito.Mockito.doThrow(new IllegalStateException(
                        "This backup is still running. Wait for it to finish before deleting it."))
                .when(artifacts).delete(id);

        mockMvc.perform(post("/executions/{id}/delete", id))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attribute("error",
                        org.hamcrest.Matchers.containsString("still running")));
    }

    @Test
    void deletingABackupRemovedInAnotherTabDoesNotBlowUp() throws Exception {
        UUID id = UUID.randomUUID();
        org.mockito.Mockito.doThrow(new NoSuchElementException("gone")).when(artifacts).delete(id);

        mockMvc.perform(post("/executions/{id}/delete", id))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attribute("error", "That backup no longer exists"));
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

    private static BackupExecution succeeded() {
        return BackupExecution.started(UUID.randomUUID(), TARGET_ID, STARTED)
                .succeeded("/backups/shop_20260909_100000.sql.gz", 8192L, STARTED.plusSeconds(90));
    }

    private static DatabaseTarget target(String name) {
        return DatabaseTarget.builder()
                .id(TARGET_ID).name(name).host("127.0.0.1").port(3306)
                .databaseName("shop").username("backup")
                .passwordCiphertext("Y2lwaGVydGV4dA==").createdAt(STARTED)
                .build();
    }
}
