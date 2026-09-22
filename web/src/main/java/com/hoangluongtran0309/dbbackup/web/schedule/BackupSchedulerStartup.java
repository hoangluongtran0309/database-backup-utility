package com.hoangluongtran0309.dbbackup.web.schedule;

import org.quartz.Scheduler;
import org.quartz.SchedulerException;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import com.hoangluongtran0309.dbbackup.application.schedule.ManageBackupScheduleService;

import lombok.RequiredArgsConstructor;

/** Rebuilds triggers only after interrupted execution rows have been repaired. */
@Component
@RequiredArgsConstructor
class BackupSchedulerStartup {

    private final ManageBackupScheduleService schedules;
    private final Scheduler scheduler;

    @EventListener(ApplicationReadyEvent.class)
    @Order(100)
    void reconcileAndStart() throws SchedulerException {
        schedules.reconcileScheduler();
        scheduler.start();
    }
}
