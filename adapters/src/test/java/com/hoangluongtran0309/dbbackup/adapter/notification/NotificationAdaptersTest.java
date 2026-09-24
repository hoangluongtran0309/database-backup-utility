package com.hoangluongtran0309.dbbackup.adapter.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.time.Duration;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

import com.hoangluongtran0309.dbbackup.core.model.EmailNotificationSettings;
import com.hoangluongtran0309.dbbackup.core.model.NotificationEventType;
import com.hoangluongtran0309.dbbackup.core.model.NotificationMessage;
import com.hoangluongtran0309.dbbackup.core.model.TelegramNotificationSettings;
import com.hoangluongtran0309.dbbackup.core.model.WebhookNotificationSettings;
import org.mockito.ArgumentCaptor;

class NotificationAdaptersTest {
    @Test void genericWebhookSendsStableJsonIncludingTestIdentity() throws Exception {
        HttpClient client = successfulClient("{}");
        NotificationMessage message = NotificationMessage.test(UUID.randomUUID(), "automation",
                Instant.parse("2026-09-24T01:00:00Z"));
        new WebhookNotificationAdapter(new HttpNotificationSupport(client))
                .send(new WebhookNotificationSettings("https://hooks.example.test/notify"), message);
        HttpRequest request = capturedRequest(client);
        assertThat(request.uri().toString()).isEqualTo("https://hooks.example.test/notify");
        assertThat(body(request)).contains("\"event\":\"TEST\"", "\"isTest\":true",
                "\"name\":\"automation\"", "\"sourceTarget\"");
    }

    @Test void telegramUsesBotEndpointAndRejectsAnOkFalseResponse() throws Exception {
        HttpClient client = successfulClient("{\"ok\":false}");
        assertThatThrownBy(() -> new TelegramNotificationAdapter("https://telegram.example.test",
                new HttpNotificationSupport(client)).send(
                        new TelegramNotificationSettings("123:token", "chat-1"), testMessage()))
                .hasMessageContaining("rejected");
        HttpRequest request = capturedRequest(client);
        assertThat(request.uri().getPath()).isEqualTo("/bot123:token/sendMessage");
        assertThat(body(request)).contains("chat-1", "Test notification");
    }

    @Test void nonSuccessfulHttpResponseIsAFailureWithoutLeakingTheUrl() throws Exception {
        HttpClient client = responseClient(503, "no");
        assertThatThrownBy(() -> new WebhookNotificationAdapter(new HttpNotificationSupport(client)).send(
                new WebhookNotificationSettings("https://hooks.example.test/notify?token=secret"), testMessage()))
                .hasMessage("Webhook notification returned HTTP 503")
                .satisfies(error -> assertThat(error.getMessage()).doesNotContain("secret"));
    }

    @Test void httpRequestsUseTheFiveSecondRequestTimeout() throws Exception {
        HttpClient client = successfulClient("{}");
        new WebhookNotificationAdapter(new HttpNotificationSupport(client)).send(
                new WebhookNotificationSettings("https://hooks.example.test/notify"), testMessage());
        assertThat(capturedRequest(client).timeout()).contains(Duration.ofSeconds(5));
    }

    @Test void interruptionIsRestoredAndReportedClearly() throws Exception {
        HttpClient client = mock(HttpClient.class);
        doThrow(new InterruptedException("stop")).when(client)
                .send(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
        try {
            assertThatThrownBy(() -> new HttpNotificationSupport(client).postJson(
                    "https://hooks.example.test/notify", "{}", "Webhook"))
                    .hasMessage("Webhook notification was interrupted");
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
    }

    @SuppressWarnings("unchecked")
    @Test void emailFailsClearlyWhenSmtpIsNotConfigured() {
        ObjectProvider<JavaMailSender> provider = mock(ObjectProvider.class);
        assertThatThrownBy(() -> new EmailNotificationAdapter(provider, "dbbackup@example.com", "")
                .send(new EmailNotificationSettings("ops@example.com"), testMessage()))
                .hasMessageContaining("SMTP is not configured");
    }

    @SuppressWarnings("unchecked")
    @Test void emailBuildsSubjectAndBody() {
        ObjectProvider<JavaMailSender> provider = mock(ObjectProvider.class);
        JavaMailSender sender = mock(JavaMailSender.class);
        when(provider.getIfAvailable()).thenReturn(sender);
        new EmailNotificationAdapter(provider, "dbbackup@example.com", "smtp.example.com")
                .send(new EmailNotificationSettings("ops@example.com"), testMessage());
        verify(sender).send(org.mockito.ArgumentMatchers.argThat((SimpleMailMessage mail) ->
                "dbbackup@example.com".equals(mail.getFrom())
                        && "ops@example.com".equals(mail.getTo()[0])
                        && mail.getSubject().contains("Test notification")));
    }

    private static NotificationMessage testMessage() {
        return NotificationMessage.test(UUID.randomUUID(), "test-channel", Instant.parse("2026-09-24T01:00:00Z"));
    }

    @SuppressWarnings("unchecked")
    private static HttpClient responseClient(int status, String body) throws Exception {
        HttpClient client = mock(HttpClient.class);
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(status);
        when(response.body()).thenReturn(body);
        doAnswer(invocation -> response).when(client)
                .send(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
        return client;
    }

    private static HttpClient successfulClient(String body) throws Exception {
        return responseClient(200, body);
    }

    @SuppressWarnings("unchecked")
    private static HttpRequest capturedRequest(HttpClient client) throws Exception {
        ArgumentCaptor<HttpRequest> request = ArgumentCaptor.forClass(HttpRequest.class);
        verify(client).send(request.capture(), org.mockito.ArgumentMatchers.any(HttpResponse.BodyHandler.class));
        return request.getValue();
    }

    private static String body(HttpRequest request) throws Exception {
        HttpRequest.BodyPublisher publisher = request.bodyPublisher().orElseThrow();
        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        publisher.subscribe(new java.util.concurrent.Flow.Subscriber<>() {
            @Override public void onSubscribe(java.util.concurrent.Flow.Subscription subscription) {
                subscription.request(Long.MAX_VALUE);
            }
            @Override public void onNext(java.nio.ByteBuffer item) {
                byte[] chunk = new byte[item.remaining()];
                item.get(chunk);
                bytes.writeBytes(chunk);
            }
            @Override public void onError(Throwable throwable) { throw new AssertionError(throwable); }
            @Override public void onComplete() { }
        });
        return bytes.toString(java.nio.charset.StandardCharsets.UTF_8);
    }
}
