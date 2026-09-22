package com.hoangluongtran0309.dbbackup.core.port;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.hoangluongtran0309.dbbackup.core.model.BackupSchedule;

/** Persistent source of truth for recurring backup schedules. */
public interface BackupScheduleRepository {

    BackupSchedule save(BackupSchedule schedule);

    List<BackupSchedule> findAll();

    Optional<BackupSchedule> findById(UUID id);

    long countForTarget(UUID targetId);

    void deleteById(UUID id);
}
