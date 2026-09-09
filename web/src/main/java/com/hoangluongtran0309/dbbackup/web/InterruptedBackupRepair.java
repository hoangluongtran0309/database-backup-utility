package com.hoangluongtran0309.dbbackup.web;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import com.hoangluongtran0309.dbbackup.application.backup.RunBackupService;

import lombok.RequiredArgsConstructor;

/**
 * Repairs executions left RUNNING by a process that is no longer here.
 *
 * <p>Separate from {@link BackupExecutorConfig} on purpose: that class defines
 * the executor {@code RunBackupService} depends on, so a configuration holding
 * both would be a bean cycle.
 */
@Component
@RequiredArgsConstructor
class InterruptedBackupRepair {

    private final RunBackupService runBackupService;

    @EventListener(ApplicationReadyEvent.class)
    void repairInterruptedBackups() {
        runBackupService.failInterruptedBackups();
    }
}
