package com.hoangluongtran0309.dbbackup.web.schedule;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import java.util.Properties;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.quartz.Scheduler;
import org.quartz.impl.StdSchedulerFactory;

import com.hoangluongtran0309.dbbackup.core.exception.InvalidScheduleException;
import com.hoangluongtran0309.dbbackup.core.model.BackupSchedule;

class QuartzBackupSchedulerTest {

    private Scheduler quartz;
    private QuartzBackupScheduler adapter;

    @BeforeEach
    void setUp() throws Exception {
        Properties properties = new Properties();
        properties.setProperty("org.quartz.scheduler.instanceName", "test-" + UUID.randomUUID());
        properties.setProperty("org.quartz.threadPool.threadCount", "1");
        properties.setProperty("org.quartz.jobStore.class", "org.quartz.simpl.RAMJobStore");
        quartz = new StdSchedulerFactory(properties).getScheduler();
        adapter = new QuartzBackupScheduler(quartz);
    }

    @AfterEach
    void tearDown() throws Exception {
        quartz.shutdown(false);
    }

    @Test
    void materializesAndReschedulesACronTrigger() {
        BackupSchedule schedule = schedule("0 0 2 * * ?", true);

        adapter.replace(schedule);
        Instant first = adapter.nextFireTime(schedule.getId()).orElseThrow();
        adapter.replace(schedule.edited(
                schedule.getTargetId(), schedule.getName(), "0 0 3 * * ?", schedule.getZoneId(), true,
                schedule.getUpdatedAt().plusSeconds(1)));

        assertThat(adapter.nextFireTime(schedule.getId())).isPresent();
        assertThat(adapter.nextFireTime(schedule.getId()).orElseThrow()).isNotEqualTo(first);
    }

    @Test
    void pausingRemovesTheRuntimeTrigger() {
        BackupSchedule enabled = schedule("0 0 2 * * ?", true);
        adapter.replace(enabled);

        adapter.replace(enabled.edited(
                enabled.getTargetId(), enabled.getName(), enabled.getCronExpression(), enabled.getZoneId(), false,
                enabled.getUpdatedAt().plusSeconds(1)));

        assertThat(adapter.nextFireTime(enabled.getId())).isEmpty();
    }

    @Test
    void rejectsInvalidQuartzSyntax() {
        BackupSchedule invalid = schedule("a b c d e f", true);

        assertThatThrownBy(() -> adapter.validate(invalid))
                .isInstanceOf(InvalidScheduleException.class)
                .hasMessageContaining("valid Quartz cron");
    }

    @Test
    void reconciliationRemovesRuntimeJobsNoLongerInTheDatabase() {
        BackupSchedule kept = schedule("0 0 2 * * ?", true);
        BackupSchedule removed = schedule("0 0 4 * * ?", true);
        adapter.replace(kept);
        adapter.replace(removed);

        adapter.reconcile(List.of(kept));

        assertThat(adapter.nextFireTime(kept.getId())).isPresent();
        assertThat(adapter.nextFireTime(removed.getId())).isEmpty();
    }

    private static BackupSchedule schedule(String cron, boolean enabled) {
        Instant now = Instant.parse("2026-09-21T01:00:00Z");
        return BackupSchedule.builder()
                .id(UUID.randomUUID())
                .targetId(UUID.randomUUID())
                .name("Nightly")
                .cronExpression(cron)
                .zoneId("Asia/Ho_Chi_Minh")
                .enabled(enabled)
                .createdAt(now)
                .updatedAt(now)
                .build();
    }
}
