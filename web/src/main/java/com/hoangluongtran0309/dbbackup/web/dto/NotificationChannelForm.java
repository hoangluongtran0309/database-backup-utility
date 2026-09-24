package com.hoangluongtran0309.dbbackup.web.dto;

import com.hoangluongtran0309.dbbackup.application.notification.SaveNotificationChannelCommand;
import com.hoangluongtran0309.dbbackup.core.model.NotificationChannel;
import com.hoangluongtran0309.dbbackup.core.model.NotificationChannelType;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class NotificationChannelForm {
    @NotBlank @Size(max = 100) private String name;
    @NotNull private NotificationChannelType type;
    private String botToken;
    @Size(max = 100) private String chatId;
    private String webhookUrl;
    @Size(max = 255) private String emailTo;

    public static NotificationChannelForm blank(NotificationChannelType type) {
        NotificationChannelForm form = new NotificationChannelForm();
        form.setType(type);
        return form;
    }
    public static NotificationChannelForm of(NotificationChannel channel) {
        NotificationChannelForm form = blank(channel.getType());
        form.setName(channel.getName());
        form.setChatId(channel.getChatId());
        form.setEmailTo(channel.getEmailTo());
        return form;
    }
    public SaveNotificationChannelCommand toCommand() {
        return new SaveNotificationChannelCommand(name, type, botToken, chatId, webhookUrl, emailTo);
    }
    public void clearSecrets() { botToken = null; webhookUrl = null; }
}
