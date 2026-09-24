package com.hoangluongtran0309.dbbackup.core.port;

import com.hoangluongtran0309.dbbackup.core.model.NotificationChannelSettings;
import com.hoangluongtran0309.dbbackup.core.model.NotificationChannelType;
import com.hoangluongtran0309.dbbackup.core.model.NotificationMessage;

public interface NotificationPort {
    NotificationChannelType supportedType();
    void send(NotificationChannelSettings settings, NotificationMessage message);
}
