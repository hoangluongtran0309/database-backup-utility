package com.hoangluongtran0309.dbbackup.application.schedule;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;

import com.hoangluongtran0309.dbbackup.application.backup.RunBackupService;
import com.hoangluongtran0309.dbbackup.core.exception.InvalidScheduleException;
import com.hoangluongtran0309.dbbackup.core.model.BackupSchedule;
import com.hoangluongtran0309.dbbackup.core.port.BackupScheduleRepository;
import com.hoangluongtran0309.dbbackup.core.port.BackupSchedulerPort;
import com.hoangluongtran0309.dbbackup.core.port.DatabaseTargetRepository;

import lombok.RequiredArgsConstructor;

/** Creates durable schedule definitions and keeps Quartz's derived triggers in sync. */
@Service
@RequiredArgsConstructor
public class ManageBackupScheduleService {

    public record ScheduleView(BackupSchedule schedule, Optional<Instant> nextFireTime) {
    }

    private final BackupScheduleRepository schedules;
    private final DatabaseTargetRepository targets;
    private final BackupSchedulerPort scheduler;
    private final RunBackupService backups;
    private final Clock clock;

    public List<ScheduleView> listAll() {
        List<BackupSchedule> all = schedules.findAll();
        Map<UUID, Instant> next = scheduler.nextFireTimes(
                all.stream().map(BackupSchedule::getId).toList());
        return all.stream()
                .map(schedule -> new ScheduleView(schedule, Optional.ofNullable(next.get(schedule.getId()))))
                .toList();
    }

    public BackupSchedule get(UUID id) {
        return schedules.findById(id)
                .orElseThrow(() -> new NoSuchElementException("No backup schedule with id " + id));
    }

    public BackupSchedule create(SaveBackupScheduleCommand command) {
        requireTarget(command.targetId());
        Instant now = clock.instant();
        BackupSchedule schedule = BackupSchedule.builder()
                .id(UUID.randomUUID())
                .targetId(command.targetId())
                .name(command.name())
                .cronExpression(command.cronExpression())
                .zoneId(command.zoneId())
                .enabled(command.enabled())
                .createdAt(now)
                .updatedAt(now)
                .build();
        scheduler.validate(schedule);
        BackupSchedule saved = schedules.save(schedule);
        try {
            scheduler.replace(saved);
            return saved;
        } catch (RuntimeException e) {
            schedules.deleteById(saved.getId());
            throw e;
        }
    }

    public BackupSchedule edit(UUID id, SaveBackupScheduleCommand command) {
        BackupSchedule current = get(id);
        requireTarget(command.targetId());
        BackupSchedule edited = current.edited(
                command.targetId(), command.name(), command.cronExpression(), command.zoneId(),
                command.enabled(), clock.instant());
        scheduler.validate(edited);
        BackupSchedule saved = schedules.save(edited);
        try {
            scheduler.replace(saved);
            return saved;
        } catch (RuntimeException e) {
            schedules.save(current);
            try {
                scheduler.replace(current);
            } catch (RuntimeException suppressed) {
                e.addSuppressed(suppressed);
            }
            throw e;
        }
    }

    public void delete(UUID id) {
        BackupSchedule current = schedules.findById(id).orElse(null);
        if (current == null) {
            return;
        }
        scheduler.delete(id);
        try {
            schedules.deleteById(id);
        } catch (RuntimeException e) {
            scheduler.replace(current);
            throw e;
        }
    }

    /** Called by the Quartz job; a disabled/deleted schedule is a harmless stale fire. */
    public Optional<UUID> startScheduledBackup(UUID scheduleId) {
        return schedules.findById(scheduleId)
                .filter(BackupSchedule::isEnabled)
                .map(schedule -> backups.start(schedule.getTargetId()));
    }

    /** Rebuilds all volatile triggers after the metadata store is ready. */
    public void reconcileScheduler() {
        scheduler.reconcile(schedules.findAll());
    }

    private void requireTarget(UUID targetId) {
        if (targetId == null || targets.findById(targetId).isEmpty()) {
            throw new InvalidScheduleException("targetId", "Choose an existing backup target");
        }
    }
}
