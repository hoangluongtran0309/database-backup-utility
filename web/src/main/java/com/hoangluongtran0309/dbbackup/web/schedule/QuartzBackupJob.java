package com.hoangluongtran0309.dbbackup.web.schedule;

import java.util.UUID;

import org.quartz.DisallowConcurrentExecution;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.quartz.QuartzJobBean;

import com.hoangluongtran0309.dbbackup.application.schedule.ManageBackupScheduleService;

/** Thin Quartz inbound adapter: every fire enters the ordinary backup use case. */
@DisallowConcurrentExecution
public class QuartzBackupJob extends QuartzJobBean {

    private ManageBackupScheduleService schedules;

    @Autowired
    public void setSchedules(ManageBackupScheduleService schedules) {
        this.schedules = schedules;
    }

    @Override
    protected void executeInternal(JobExecutionContext context) throws JobExecutionException {
        String value = context.getMergedJobDataMap().getString(QuartzBackupScheduler.SCHEDULE_ID);
        try {
            schedules.startScheduledBackup(UUID.fromString(value));
        } catch (RuntimeException e) {
            throw new JobExecutionException("Could not start scheduled backup " + value, e, false);
        }
    }
}
