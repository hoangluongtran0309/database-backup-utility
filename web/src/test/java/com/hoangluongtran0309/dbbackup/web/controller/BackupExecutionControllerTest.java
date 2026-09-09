package com.hoangluongtran0309.dbbackup.web.controller;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

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
        when(targets.listAll()).thenReturn(List.of(target()));

        mockMvc.perform(get("/executions"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("production")))
                .andExpect(content().string(containsString("Succeeded")))
                .andExpect(content().string(containsString("2026-09-09 10:00:00 UTC")));
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
        when(targets.listAll()).thenReturn(List.of(target()));

        mockMvc.perform(get("/executions/{id}", execution.getId()))
                .andExpect(status().isOk())
                .andExpect(view().name("execution/detail"))
                .andExpect(content().string(containsString("/backups/shop_20260909_100000.sql")))
                .andExpect(content().string(containsString("8192 bytes")));
    }

    @Test
    void detailOfARunningBackupSaysSoAndHidesTheArtifactFields() throws Exception {
        BackupExecution execution = BackupExecution.started(UUID.randomUUID(), TARGET_ID, STARTED);
        when(executions.findById(execution.getId())).thenReturn(Optional.of(execution));
        when(targets.listAll()).thenReturn(List.of(target()));

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
        when(targets.listAll()).thenReturn(List.of(target()));

        mockMvc.perform(get("/executions/{id}", execution.getId()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Why it failed")))
                .andExpect(content().string(containsString("Access denied for user")))
                .andExpect(content().string(containsString("Failed")));
    }

    private static BackupExecution succeeded() {
        return BackupExecution.started(UUID.randomUUID(), TARGET_ID, STARTED)
                .succeeded("/backups/shop_20260909_100000.sql", 8192L, STARTED.plusSeconds(90));
    }

    private static DatabaseTarget target() {
        return DatabaseTarget.builder()
                .id(TARGET_ID).name("production").host("127.0.0.1").port(3306)
                .databaseName("shop").username("backup")
                .passwordCiphertext("Y2lwaGVydGV4dA==").createdAt(STARTED)
                .build();
    }
}
