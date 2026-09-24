package com.hoangluongtran0309.dbbackup.adapter.notification;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

import com.hoangluongtran0309.dbbackup.core.model.EmailNotificationSettings;
import com.hoangluongtran0309.dbbackup.core.model.NotificationChannelSettings;
import com.hoangluongtran0309.dbbackup.core.model.NotificationChannelType;
import com.hoangluongtran0309.dbbackup.core.model.NotificationMessage;
import com.hoangluongtran0309.dbbackup.core.port.NotificationPort;

@Component
public class EmailNotificationAdapter implements NotificationPort {
    private final ObjectProvider<JavaMailSender> senderProvider;
    private final String from;
    private final String host;

    public EmailNotificationAdapter(ObjectProvider<JavaMailSender> senderProvider,
            @Value("${spring.mail.from:dbbackup@localhost}") String from,
            @Value("${spring.mail.host:}") String host) {
        this.senderProvider = senderProvider;
        this.from = from;
        this.host = host;
    }
    @Override public NotificationChannelType supportedType() { return NotificationChannelType.EMAIL; }
    @Override public void send(NotificationChannelSettings settings, NotificationMessage message) {
        if (!(settings instanceof EmailNotificationSettings email)) {
            throw new IllegalArgumentException("Email settings required");
        }
        JavaMailSender sender = senderProvider.getIfAvailable();
        if (sender == null || host == null || host.isBlank()) {
            throw new IllegalStateException("SMTP is not configured — set DBBACKUP_SMTP_HOST and credentials");
        }
        SimpleMailMessage mail = new SimpleMailMessage();
        mail.setFrom(from);
        mail.setTo(email.recipient());
        mail.setSubject(message.title());
        mail.setText(message.text());
        sender.send(mail);
    }
}
