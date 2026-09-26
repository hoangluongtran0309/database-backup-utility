package com.hoangluongtran0309.dbbackup.cli;

import java.io.Console;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

final class ApiClient {
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final ObjectMapper json;
    private final URI baseUri;
    private final String authorization;

    ApiClient(CliArguments arguments, ObjectMapper json, Map<String, String> environment) {
        this.json = json;
        this.baseUri = validateBaseUri(arguments.server(), arguments.allowHttp());
        String password = resolvePassword(arguments, environment);
        authorization = "Basic " + Base64.getEncoder().encodeToString(
                (arguments.username() + ":" + password).getBytes(StandardCharsets.UTF_8));
    }

    JsonNode request(String method, String path, Object body) {
        HttpRequest.Builder request = base(path).header("Accept", "application/json");
        try {
            if (body == null) {
                request.method(method, HttpRequest.BodyPublishers.noBody());
            } else {
                request.header("Content-Type", "application/json")
                        .method(method, HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
            }
            HttpResponse<String> response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
            JsonNode envelope = json.readTree(response.body());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                JsonNode error = envelope.path("error");
                throw new CliException(exitCode(response.statusCode()),
                        error.path("message").asText("HTTP " + response.statusCode()), envelope);
            }
            return envelope;
        } catch (CliException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CliException(70, "Interrupted while contacting the server");
        } catch (IOException e) {
            throw new CliException(5, "Could not contact " + baseUri + ": " + e.getMessage());
        }
    }

    void download(String path, java.nio.file.Path destination) {
        java.nio.file.Path temporary = null;
        try {
            HttpResponse<InputStream> response = http.send(
                    base(path).timeout(Duration.ofHours(24)).GET().build(),
                    HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream content = response.body()) {
                if (response.statusCode() < 200 || response.statusCode() >= 300) {
                    String message = "HTTP " + response.statusCode();
                    JsonNode envelope = null;
                    try {
                        envelope = json.readTree(content.readNBytes(65_536));
                        message = envelope.path("error").path("message").asText(message);
                    } catch (IOException ignored) { }
                    throw new CliException(exitCode(response.statusCode()), message, envelope);
                }
                java.nio.file.Path absolute = destination.toAbsolutePath();
                temporary = Files.createTempFile(absolute.getParent(), ".dbbackup-download-", ".part");
                Files.copy(content, temporary, StandardCopyOption.REPLACE_EXISTING);
                try {
                    Files.move(temporary, absolute, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                } catch (AtomicMoveNotSupportedException ignored) {
                    Files.move(temporary, absolute, StandardCopyOption.REPLACE_EXISTING);
                }
                temporary = null;
            }
        } catch (CliException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CliException(70, "Interrupted while downloading the artifact");
        } catch (IOException e) {
            throw new CliException(5, "Could not write " + destination + ": " + e.getMessage());
        } finally {
            if (temporary != null) {
                try { Files.deleteIfExists(temporary); } catch (IOException ignored) { }
            }
        }
    }

    private HttpRequest.Builder base(String path) {
        return HttpRequest.newBuilder(baseUri.resolve(path)).timeout(Duration.ofMinutes(2))
                .header("Authorization", authorization);
    }

    private static URI validateBaseUri(String value, boolean allowHttp) {
        URI uri;
        try { uri = URI.create(value.endsWith("/") ? value : value + "/"); }
        catch (IllegalArgumentException e) { throw new CliException(2, "--server is not a valid URL"); }
        if (uri.getHost() == null || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null) {
            throw new CliException(2, "--server must contain only scheme, host, optional port and path");
        }
        boolean loopback = uri.getHost().equalsIgnoreCase("localhost") || uri.getHost().equals("127.0.0.1")
                || uri.getHost().equals("::1") || uri.getHost().equals("[::1]");
        if (!"https".equalsIgnoreCase(uri.getScheme())
                && !("http".equalsIgnoreCase(uri.getScheme()) && (loopback || allowHttp))) {
            throw new CliException(2, "HTTP is allowed only for loopback servers; use HTTPS or --allow-http");
        }
        return uri;
    }

    private static String resolvePassword(CliArguments arguments, Map<String, String> environment) {
        try {
            if (arguments.passwordFile() != null) return Files.readString(arguments.passwordFile()).stripTrailing();
            if (arguments.passwordStdin()) return new String(System.in.readNBytes(8192), StandardCharsets.UTF_8).stripTrailing();
        } catch (IOException e) {
            throw new CliException(2, "Could not read API password: " + e.getMessage());
        }
        String configured = environment.get("DBBACKUP_API_PASSWORD");
        if (configured != null && !configured.isEmpty()) return configured;
        Console console = System.console();
        if (console != null) {
            char[] password = console.readPassword("Operator password: ");
            if (password != null && password.length > 0) return new String(password);
        }
        throw new CliException(2,
                "API password is required via DBBACKUP_API_PASSWORD, --password-file, --password-stdin or a TTY prompt");
    }

    private static int exitCode(int status) {
        if (status == 400) return 2;
        if (status == 401 || status == 403) return 5;
        if (status == 404) return 3;
        if (status == 409) return 4;
        if (status >= 400 && status < 500) return 5;
        return 70;
    }
}
