package com.hoangluongtran0309.dbbackup.core.port;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.hoangluongtran0309.dbbackup.core.model.BackupSchedule;

/** Materializes persistent schedule definitions in the runtime scheduler. */
public interface BackupSchedulerPort {

    /** Parses scheduler-specific syntax without changing scheduler state. */
    void validate(BackupSchedule schedule);

    /** Creates, replaces, pauses or resumes one runtime trigger. */
    void replace(BackupSchedule schedule);

    void delete(UUID scheduleId);

    Optional<Instant> nextFireTime(UUID scheduleId);

    /** Rebuilds runtime state from the persistent source of truth at startup. */
    void reconcile(List<BackupSchedule> schedules);

    /** A bulk read for list pages; absent ids are disabled or not materialized. */
    default Map<UUID, Instant> nextFireTimes(List<UUID> scheduleIds) {
        return scheduleIds.stream()
                .map(id -> Map.entry(id, nextFireTime(id)))
                .filter(entry -> entry.getValue().isPresent())
                .collect(java.util.stream.Collectors.toMap(Map.Entry::getKey, entry -> entry.getValue().orElseThrow()));
    }
}
