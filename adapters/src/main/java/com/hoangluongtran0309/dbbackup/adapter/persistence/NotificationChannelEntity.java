package com.hoangluongtran0309.dbbackup.adapter.persistence;

import java.time.Instant;
import java.util.UUID;

import com.hoangluongtran0309.dbbackup.core.model.NotificationChannelType;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "notification_channels")
@Getter @Setter @NoArgsConstructor
class NotificationChannelEntity {
    @Id private UUID id;
    @Column(nullable = false, length = 100) private String name;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20) private NotificationChannelType type;
    @Column(name = "bot_token_enc", columnDefinition = "text") private String botTokenEnc;
    @Column(name = "chat_id", length = 100) private String chatId;
    @Column(name = "webhook_url_enc", columnDefinition = "text") private String webhookUrlEnc;
    @Column(name = "email_to", length = 255) private String emailTo;
    @Column(name = "created_at", nullable = false) private Instant createdAt;
    @Column(name = "updated_at", nullable = false) private Instant updatedAt;
    @Column(name = "last_connection_successful") private Boolean lastConnectionSuccessful;
    @Column(name = "last_connection_message", length = 500) private String lastConnectionMessage;
    @Column(name = "last_connection_checked_at") private Instant lastConnectionCheckedAt;
}
