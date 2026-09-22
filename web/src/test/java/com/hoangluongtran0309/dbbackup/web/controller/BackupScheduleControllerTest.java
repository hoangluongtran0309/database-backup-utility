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

import com.hoangluongtran0309.dbbackup.application.schedule.ManageBackupScheduleService;
import com.hoangluongtran0309.dbbackup.application.schedule.ManageBackupScheduleService.ScheduleView;
import com.hoangluongtran0309.dbbackup.application.schedule.SaveBackupScheduleCommand;
import com.hoangluongtran0309.dbbackup.application.target.ManageDatabaseTargetService;
import com.hoangluongtran0309.dbbackup.core.exception.InvalidScheduleException;
import com.hoangluongtran0309.dbbackup.core.model.BackupSchedule;
import com.hoangluongtran0309.dbbackup.core.model.DatabaseEngine;
import com.hoangluongtran0309.dbbackup.core.model.DatabaseTarget;

@WebMvcTest(BackupScheduleController.class)
@WithMockUser
class BackupScheduleControllerTest {

    private static final Instant NOW = Instant.parse("2026-09-21T01:00:00Z");
    private static final UUID TARGET_ID = UUID.randomUUID();

    @Autowired private MockMvc mockMvc;
    @MockitoBean private ManageBackupScheduleService schedules;
    @MockitoBean private ManageDatabaseTargetService targets;

    @Test
    void listsTheCronZoneStateAndNextRun() throws Exception {
        BackupSchedule schedule = schedule(true);
        when(schedules.listAll()).thenReturn(List.of(
                new ScheduleView(schedule, Optional.of(Instant.parse("2026-09-21T19:00:00Z")))));
        when(targets.listAll()).thenReturn(List.of(target()));

        mockMvc.perform(get("/schedules"))
                .andExpect(status().isOk())
                .andExpect(view().name("schedule/list"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Nightly")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("0 0 2 * * ?")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Asia/Ho_Chi_Minh")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Enabled")));
    }

    @Test
    void newFormCanBePreselectedFromATarget() throws Exception {
        when(targets.listAll()).thenReturn(List.of(target()));

        mockMvc.perform(get("/schedules/new").param("target", TARGET_ID.toString()))
                .andExpect(status().isOk())
                .andExpect(view().name("schedule/form"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "value=\"" + TARGET_ID + "\" selected")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("value=\"0 0 2 * * ?\"")));
    }

    @Test
    void createsAValidSchedule() throws Exception {
        when(targets.listAll()).thenReturn(List.of(target()));
        when(schedules.create(any())).thenReturn(schedule(true));

        mockMvc.perform(post("/schedules").with(csrf())
                        .param("name", "Nightly")
                        .param("targetId", TARGET_ID.toString())
                        .param("cronExpression", "0 0 2 * * ?")
                        .param("zoneId", "Asia/Ho_Chi_Minh")
                        .param("enabled", "true"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/schedules"));

        ArgumentCaptor<SaveBackupScheduleCommand> command =
                ArgumentCaptor.forClass(SaveBackupScheduleCommand.class);
        verify(schedules).create(command.capture());
        assertThat(command.getValue().targetId()).isEqualTo(TARGET_ID);
        assertThat(command.getValue().enabled()).isTrue();
    }

    @Test
    void placesQuartzValidationOnTheCronField() throws Exception {
        when(targets.listAll()).thenReturn(List.of(target()));
        when(schedules.create(any())).thenThrow(
                new InvalidScheduleException("cronExpression", "This is not a valid Quartz cron expression"));

        mockMvc.perform(post("/schedules").with(csrf())
                        .param("name", "Nightly")
                        .param("targetId", TARGET_ID.toString())
                        .param("cronExpression", "0 99 99 * * ?")
                        .param("zoneId", "UTC")
                        .param("enabled", "true"))
                .andExpect(status().isOk())
                .andExpect(model().attributeHasFieldErrors("form", "cronExpression"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "This is not a valid Quartz cron expression")));
    }

    @Test
    void deletingRequiresCsrf() throws Exception {
        mockMvc.perform(post("/schedules/{id}/delete", UUID.randomUUID()).with(user("operator")))
                .andExpect(status().isForbidden());
    }

    private static BackupSchedule schedule(boolean enabled) {
        return BackupSchedule.builder()
                .id(UUID.randomUUID())
                .targetId(TARGET_ID)
                .name("Nightly")
                .cronExpression("0 0 2 * * ?")
                .zoneId("Asia/Ho_Chi_Minh")
                .enabled(enabled)
                .createdAt(NOW)
                .updatedAt(NOW)
                .build();
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
