package com.hoangluongtran0309.dbbackup.application.schedule;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.hoangluongtran0309.dbbackup.application.backup.RunBackupService;
import com.hoangluongtran0309.dbbackup.core.exception.InvalidScheduleException;
import com.hoangluongtran0309.dbbackup.core.model.BackupSchedule;
import com.hoangluongtran0309.dbbackup.core.model.DatabaseEngine;
import com.hoangluongtran0309.dbbackup.core.model.DatabaseTarget;
import com.hoangluongtran0309.dbbackup.core.port.BackupScheduleRepository;
import com.hoangluongtran0309.dbbackup.core.port.BackupSchedulerPort;
import com.hoangluongtran0309.dbbackup.core.port.DatabaseTargetRepository;

@ExtendWith(MockitoExtension.class)
class ManageBackupScheduleServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-21T01:00:00Z");
    private static final UUID TARGET_ID = UUID.randomUUID();

    @Mock private BackupScheduleRepository schedules;
    @Mock private DatabaseTargetRepository targets;
    @Mock private BackupSchedulerPort scheduler;
    @Mock private RunBackupService backups;

    private ManageBackupScheduleService service;

    @BeforeEach
    void setUp() {
        service = new ManageBackupScheduleService(
                schedules, targets, scheduler, backups, Clock.fixed(NOW, ZoneOffset.UTC));
        org.mockito.Mockito.lenient().when(targets.findById(TARGET_ID)).thenReturn(Optional.of(target()));
        org.mockito.Mockito.lenient().when(schedules.save(any())).thenAnswer(call -> call.getArgument(0));
    }

    @Test
    void persistsThenMaterializesANewSchedule() {
        BackupSchedule created = service.create(command(true));

        ArgumentCaptor<BackupSchedule> saved = ArgumentCaptor.forClass(BackupSchedule.class);
        verify(schedules).save(saved.capture());
        verify(scheduler).validate(saved.getValue());
        verify(scheduler).replace(saved.getValue());
        assertThat(created.getCreatedAt()).isEqualTo(NOW);
        assertThat(created.getTargetId()).isEqualTo(TARGET_ID);
    }

    @Test
    void refusesAMissingTargetBeforeSaving() {
        UUID missing = UUID.randomUUID();
        when(targets.findById(missing)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.create(new SaveBackupScheduleCommand(
                "Nightly", missing, "0 0 2 * * ?", "UTC", true)))
                .isInstanceOf(InvalidScheduleException.class);
        verify(schedules, never()).save(any());
    }

    @Test
    void removesTheRowIfQuartzCannotMaterializeIt() {
        org.mockito.Mockito.doThrow(new IllegalStateException("scheduler unavailable"))
                .when(scheduler).replace(any());

        assertThatThrownBy(() -> service.create(command(true)))
                .hasMessageContaining("scheduler unavailable");

        ArgumentCaptor<BackupSchedule> saved = ArgumentCaptor.forClass(BackupSchedule.class);
        verify(schedules).save(saved.capture());
        verify(schedules).deleteById(saved.getValue().getId());
    }

    @Test
    void aQuartzFireStartsARegularBackup() {
        BackupSchedule schedule = schedule(true);
        UUID executionId = UUID.randomUUID();
        when(schedules.findById(schedule.getId())).thenReturn(Optional.of(schedule));
        when(backups.start(TARGET_ID)).thenReturn(executionId);

        assertThat(service.startScheduledBackup(schedule.getId())).contains(executionId);
    }

    @Test
    void aStaleFireForADisabledScheduleDoesNothing() {
        BackupSchedule schedule = schedule(false);
        when(schedules.findById(schedule.getId())).thenReturn(Optional.of(schedule));

        assertThat(service.startScheduledBackup(schedule.getId())).isEmpty();
        verify(backups, never()).start(any());
    }

    @Test
    void startupReconcilesEveryPersistedDefinition() {
        List<BackupSchedule> all = List.of(schedule(true), schedule(false));
        when(schedules.findAll()).thenReturn(all);

        service.reconcileScheduler();

        verify(scheduler).reconcile(all);
    }

    private static SaveBackupScheduleCommand command(boolean enabled) {
        return new SaveBackupScheduleCommand("Nightly", TARGET_ID, "0 0 2 * * ?", "UTC", enabled);
    }

    private static BackupSchedule schedule(boolean enabled) {
        return BackupSchedule.builder()
                .id(UUID.randomUUID())
                .targetId(TARGET_ID)
                .name("Nightly")
                .cronExpression("0 0 2 * * ?")
                .zoneId("UTC")
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
