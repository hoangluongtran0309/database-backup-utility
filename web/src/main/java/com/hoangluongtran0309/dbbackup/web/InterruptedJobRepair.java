package com.hoangluongtran0309.dbbackup.web;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import com.hoangluongtran0309.dbbackup.application.backup.RunBackupService;
import com.hoangluongtran0309.dbbackup.application.restore.RestoreBackupService;

import lombok.RequiredArgsConstructor;

/**
 * Repairs backups and restores left RUNNING by a process that is no longer here.
 *
 * <p>Separate from {@link JobExecutorConfig} on purpose: that class defines the
 * executor these services depend on, so a configuration holding both would be a
 * bean cycle. That cycle is exactly what {@code DbBackupApplicationIT} exists
 * to catch.
 */
@Component
@RequiredArgsConstructor
class InterruptedJobRepair {

    private final RunBackupService runBackupService;
    private final RestoreBackupService restoreBackupService;

    @EventListener(ApplicationReadyEvent.class)
    void repairInterruptedJobs() {
        runBackupService.failInterruptedBackups();
        restoreBackupService.failInterruptedRestores();
    }
}
