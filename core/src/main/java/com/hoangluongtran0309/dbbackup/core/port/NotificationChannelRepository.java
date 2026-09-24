package com.hoangluongtran0309.dbbackup.core.port;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.hoangluongtran0309.dbbackup.core.model.ConnectionCheck;
import com.hoangluongtran0309.dbbackup.core.model.NotificationChannel;

public interface NotificationChannelRepository {
    NotificationChannel save(NotificationChannel channel);
    Optional<NotificationChannel> findById(UUID id);
    List<NotificationChannel> findAll();
    void recordConnectionCheck(UUID id, ConnectionCheck check);
    void deleteById(UUID id);
}
