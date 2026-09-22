package com.hoangluongtran0309.dbbackup.core.model;

import java.time.Instant;
import java.time.ZoneId;
import java.time.zone.ZoneRulesException;
import java.util.UUID;

import com.hoangluongtran0309.dbbackup.core.exception.InvalidScheduleException;

import lombok.Builder;
import lombok.Getter;

/**
 * A recurring instruction to back up one registered target.
 *
 * <p>The cron expression uses Quartz's six-or-seven-field form: seconds are
 * explicit. Parsing the individual fields belongs to the scheduler adapter,
 * while this model owns the technology-independent shape and limits.
 */
@Getter
public final class BackupSchedule {

    public static final int MAX_NAME_LENGTH = 100;
    public static final int MAX_CRON_LENGTH = 120;
    public static final int MAX_ZONE_LENGTH = 64;

    private final UUID id;
    private final UUID targetId;
    private final String name;
    private final String cronExpression;
    private final String zoneId;
    private final boolean enabled;
    private final Instant createdAt;
    private final Instant updatedAt;

    @Builder
    private BackupSchedule(
            UUID id,
            UUID targetId,
            String name,
            String cronExpression,
            String zoneId,
            boolean enabled,
            Instant createdAt,
            Instant updatedAt) {

        this.id = require(id, "id", "Schedule id is required");
        this.targetId = require(targetId, "targetId", "Target is required");
        this.name = text(name, "name", "Name", MAX_NAME_LENGTH);
        this.cronExpression = cron(cronExpression);
        this.zoneId = zone(zoneId);
        this.enabled = enabled;
        this.createdAt = require(createdAt, "createdAt", "Creation timestamp is required");
        this.updatedAt = require(updatedAt, "updatedAt", "Update timestamp is required");
        if (updatedAt.isBefore(createdAt)) {
            throw new InvalidScheduleException("updatedAt", "Update timestamp cannot precede creation");
        }
    }

    public BackupSchedule edited(
            UUID targetId,
            String name,
            String cronExpression,
            String zoneId,
            boolean enabled,
            Instant updatedAt) {

        return BackupSchedule.builder()
                .id(id)
                .targetId(targetId)
                .name(name)
                .cronExpression(cronExpression)
                .zoneId(zoneId)
                .enabled(enabled)
                .createdAt(createdAt)
                .updatedAt(updatedAt)
                .build();
    }

    private static String cron(String value) {
        String cron = text(value, "cronExpression", "Cron expression", MAX_CRON_LENGTH);
        int fields = cron.split("\\s+").length;
        if (fields < 6 || fields > 7) {
            throw new InvalidScheduleException(
                    "cronExpression", "Quartz cron expressions must contain six or seven fields");
        }
        return cron;
    }

    private static String zone(String value) {
        String zone = text(value, "zoneId", "Time zone", MAX_ZONE_LENGTH);
        try {
            return ZoneId.of(zone).getId();
        } catch (ZoneRulesException e) {
            throw new InvalidScheduleException("zoneId", "Unknown time zone '%s'".formatted(zone));
        }
    }

    private static String text(String value, String field, String label, int maxLength) {
        if (value == null || value.isBlank()) {
            throw new InvalidScheduleException(field, label + " is required");
        }
        String stripped = value.strip();
        if (stripped.length() > maxLength) {
            throw new InvalidScheduleException(
                    field, "%s must be at most %d characters".formatted(label, maxLength));
        }
        return stripped;
    }

    private static <T> T require(T value, String field, String message) {
        if (value == null) {
            throw new InvalidScheduleException(field, message);
        }
        return value;
    }
}
