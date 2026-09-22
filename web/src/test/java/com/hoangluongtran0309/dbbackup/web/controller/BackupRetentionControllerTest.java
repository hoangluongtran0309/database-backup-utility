package com.hoangluongtran0309.dbbackup.web.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.hoangluongtran0309.dbbackup.application.retention.ManageBackupRetentionService;
import com.hoangluongtran0309.dbbackup.application.retention.ManageBackupRetentionService.RetentionView;
import com.hoangluongtran0309.dbbackup.application.retention.SaveBackupRetentionPolicyCommand;
import com.hoangluongtran0309.dbbackup.core.model.BackupRetentionPolicy;
import com.hoangluongtran0309.dbbackup.core.model.DatabaseEngine;
import com.hoangluongtran0309.dbbackup.core.model.DatabaseTarget;

@WebMvcTest(BackupRetentionController.class)
@WithMockUser
class BackupRetentionControllerTest {

    private static final UUID TARGET_ID = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-09-22T04:00:00Z");

    @Autowired private MockMvc mockMvc;
    @MockitoBean private ManageBackupRetentionService retention;

    @Test
    void listsEnabledPolicyAndItsLatestOutcome() throws Exception {
        BackupRetentionPolicy policy = BackupRetentionPolicy.create(TARGET_ID, 7, NOW.minusSeconds(60))
                .completed(3, null, NOW);
        when(retention.listAll()).thenReturn(List.of(new RetentionView(target(), Optional.of(policy))));

        mockMvc.perform(get("/retention"))
                .andExpect(status().isOk())
                .andExpect(view().name("retention/list"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("production")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Keep 7 successful")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Deleted 3")));
    }

    @Test
    void showsAStoredFailureWithoutHidingPartialProgress() throws Exception {
        BackupRetentionPolicy policy = BackupRetentionPolicy.create(TARGET_ID, 3, NOW.minusSeconds(60))
                .completed(1, "permission denied", NOW);
        when(retention.listAll()).thenReturn(List.of(new RetentionView(target(), Optional.of(policy))));

        mockMvc.perform(get("/retention"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Failed")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("permission denied")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("1 deleted before the failure")));
    }

    @Test
    void opensABlankPolicyForAnUnconfiguredTarget() throws Exception {
        when(retention.getTarget(TARGET_ID)).thenReturn(target());
        when(retention.findForTarget(TARGET_ID)).thenReturn(Optional.empty());

        mockMvc.perform(get("/retention/{targetId}/edit", TARGET_ID))
                .andExpect(status().isOk())
                .andExpect(view().name("retention/form"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Enable retention")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("production")));
    }

    @Test
    void savesAValidPolicy() throws Exception {
        when(retention.getTarget(TARGET_ID)).thenReturn(target());
        when(retention.save(any(), any())).thenReturn(BackupRetentionPolicy.create(TARGET_ID, 5, NOW));

        mockMvc.perform(post("/retention/{targetId}", TARGET_ID).with(csrf())
                        .param("keepSuccessful", "5"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/retention"));

        ArgumentCaptor<SaveBackupRetentionPolicyCommand> command =
                ArgumentCaptor.forClass(SaveBackupRetentionPolicyCommand.class);
        verify(retention).save(org.mockito.ArgumentMatchers.eq(TARGET_ID), command.capture());
        assertThat(command.getValue().keepSuccessful()).isEqualTo(5);
    }

    @Test
    void refusesZeroWithoutCallingTheService() throws Exception {
        when(retention.getTarget(TARGET_ID)).thenReturn(target());
        when(retention.findForTarget(TARGET_ID)).thenReturn(Optional.empty());

        mockMvc.perform(post("/retention/{targetId}", TARGET_ID).with(csrf())
                        .param("keepSuccessful", "0"))
                .andExpect(status().isOk())
                .andExpect(model().attributeHasFieldErrors("form", "keepSuccessful"));
    }

    @Test
    void disablingRequiresCsrf() throws Exception {
        mockMvc.perform(post("/retention/{targetId}/disable", TARGET_ID).with(user("operator")))
                .andExpect(status().isForbidden());
    }

    private static DatabaseTarget target() {
        return DatabaseTarget.builder()
                .id(TARGET_ID)
                .name("production")
                .engine(DatabaseEngine.MYSQL)
                .host("db.internal")
                .port(3306)
                .databaseName("shop")
                .username("backup")
                .passwordCiphertext("sealed")
                .createdAt(NOW)
                .build();
    }
}
