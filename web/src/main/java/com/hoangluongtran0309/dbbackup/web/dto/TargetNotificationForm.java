package com.hoangluongtran0309.dbbackup.web.dto;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.hoangluongtran0309.dbbackup.core.model.NotificationEventType;
import com.hoangluongtran0309.dbbackup.core.model.TargetNotificationSubscription;

import lombok.Data;

@Data
public class TargetNotificationForm {
    private List<Row> rows = new ArrayList<>();

    public List<TargetNotificationSubscription> toSubscriptions() {
        return rows.stream().filter(Row::isIncluded)
                .map(row -> new TargetNotificationSubscription(row.channelId, row.events)).toList();
    }

    @Data
    public static class Row {
        private UUID channelId;
        private boolean included;
        private Set<NotificationEventType> events = EnumSet.of(
                NotificationEventType.BACKUP_FAILED, NotificationEventType.RESTORE_FAILED);
    }
}
