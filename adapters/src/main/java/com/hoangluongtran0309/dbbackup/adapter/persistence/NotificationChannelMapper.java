package com.hoangluongtran0309.dbbackup.adapter.persistence;

import com.hoangluongtran0309.dbbackup.core.model.ConnectionCheck;
import com.hoangluongtran0309.dbbackup.core.model.NotificationChannel;

final class NotificationChannelMapper {
    private NotificationChannelMapper() {}

    static NotificationChannelEntity toEntity(NotificationChannel channel) {
        NotificationChannelEntity entity = new NotificationChannelEntity();
        entity.setId(channel.getId());
        entity.setName(channel.getName());
        entity.setType(channel.getType());
        entity.setBotTokenEnc(channel.getBotTokenCiphertext());
        entity.setChatId(channel.getChatId());
        entity.setWebhookUrlEnc(channel.getWebhookUrlCiphertext());
        entity.setEmailTo(channel.getEmailTo());
        entity.setCreatedAt(channel.getCreatedAt());
        entity.setUpdatedAt(channel.getUpdatedAt());
        ConnectionCheck check = channel.getLastConnectionCheck();
        if (check != null) {
            entity.setLastConnectionSuccessful(check.successful());
            entity.setLastConnectionMessage(check.message());
            entity.setLastConnectionCheckedAt(check.checkedAt());
        }
        return entity;
    }

    static NotificationChannel toDomain(NotificationChannelEntity entity) {
        ConnectionCheck check = entity.getLastConnectionSuccessful() == null
                || entity.getLastConnectionCheckedAt() == null ? null : new ConnectionCheck(
                        entity.getLastConnectionSuccessful(), entity.getLastConnectionMessage(),
                        entity.getLastConnectionCheckedAt());
        return NotificationChannel.builder()
                .id(entity.getId()).name(entity.getName()).type(entity.getType())
                .botTokenCiphertext(entity.getBotTokenEnc()).chatId(entity.getChatId())
                .webhookUrlCiphertext(entity.getWebhookUrlEnc()).emailTo(entity.getEmailTo())
                .createdAt(entity.getCreatedAt()).updatedAt(entity.getUpdatedAt())
                .lastConnectionCheck(check).build();
    }
}
