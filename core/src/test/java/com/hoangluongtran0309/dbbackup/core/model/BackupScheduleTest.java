package com.hoangluongtran0309.dbbackup.core.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.hoangluongtran0309.dbbackup.core.exception.InvalidScheduleException;

class BackupScheduleTest {

    private static final Instant NOW = Instant.parse("2026-09-21T01:00:00Z");

    @Test
    void normalisesTextAndAcceptsAnIanaZone() {
        BackupSchedule schedule = schedule("  Nightly  ", " 0 0 2 * * ? ", "Asia/Ho_Chi_Minh");

        assertThat(schedule.getName()).isEqualTo("Nightly");
        assertThat(schedule.getCronExpression()).isEqualTo("0 0 2 * * ?");
        assertThat(schedule.getZoneId()).isEqualTo("Asia/Ho_Chi_Minh");
    }

    @Test
    void rejectsUnixFiveFieldCronBecauseQuartzIncludesSeconds() {
        assertThatThrownBy(() -> schedule("Nightly", "0 2 * * *", "UTC"))
                .isInstanceOf(InvalidScheduleException.class)
                .hasMessageContaining("six or seven fields");
    }

    @Test
    void rejectsAnUnknownZone() {
        assertThatThrownBy(() -> schedule("Nightly", "0 0 2 * * ?", "Moon/Sea_of_Tranquility"))
                .isInstanceOf(InvalidScheduleException.class)
                .hasMessageContaining("Unknown time zone");
    }

    private static BackupSchedule schedule(String name, String cron, String zone) {
        return BackupSchedule.builder()
                .id(UUID.randomUUID())
                .targetId(UUID.randomUUID())
                .name(name)
                .cronExpression(cron)
                .zoneId(zone)
                .enabled(true)
                .createdAt(NOW)
                .updatedAt(NOW)
                .build();
    }
}
