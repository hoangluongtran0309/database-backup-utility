package com.hoangluongtran0309.dbbackup.adapter.notification;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

final class HttpNotificationSupport {
    private final HttpClient client;

    HttpNotificationSupport() {
        this(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build());
    }

    HttpNotificationSupport(HttpClient client) {
        this.client = client;
    }

    String postJson(String url, String json, String channel) {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(5))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json)).build();
        try {
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException(channel + " notification returned HTTP " + response.statusCode());
            }
            return response.body();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(channel + " notification was interrupted", e);
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException(channel + " notification delivery failed", e);
        }
    }
}
