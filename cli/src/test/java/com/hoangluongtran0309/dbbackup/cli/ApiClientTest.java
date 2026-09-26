package com.hoangluongtran0309.dbbackup.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Base64;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;

class ApiClientTest {
    @TempDir Path temporaryDirectory;
    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) server.stop(0);
    }

    @Test
    void sendsBasicAuthenticationAndReadsTheApiEnvelope() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        String expected = "Basic " + Base64.getEncoder().encodeToString(
                "operator:correct horse".getBytes(StandardCharsets.UTF_8));
        server.createContext("/api/v1/targets", exchange -> {
            assertThat(exchange.getRequestHeaders().getFirst("Authorization")).isEqualTo(expected);
            byte[] response = "{\"ok\":true,\"data\":[{\"name\":\"production\"}],\"error\":null}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();

        ApiClient client = new ApiClient(arguments(baseUrl()), new ObjectMapper(),
                Map.of("DBBACKUP_API_PASSWORD", "correct horse"));

        assertThat(client.request("GET", "api/v1/targets", null).path("data").get(0).path("name").asText())
                .isEqualTo("production");
    }

    @Test
    void mapsHttpConflictToTheDocumentedCliExitCode() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/targets", exchange -> {
            byte[] response = "{\"ok\":false,\"data\":null,\"error\":{\"code\":\"CONFLICT\",\"message\":\"already exists\"}}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(409, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        ApiClient client = new ApiClient(arguments(baseUrl()), new ObjectMapper(),
                Map.of("DBBACKUP_API_PASSWORD", "secret"));

        assertThatThrownBy(() -> client.request("GET", "api/v1/targets", null))
                .isInstanceOfSatisfying(CliException.class, error -> {
                    assertThat(error.exitCode()).isEqualTo(4);
                    assertThat(error).hasMessage("already exists");
                });
    }

    @Test
    void refusesClearTextCredentialsToANonLoopbackServerByDefault() {
        assertThatThrownBy(() -> new ApiClient(arguments("http://example.com"), new ObjectMapper(),
                Map.of("DBBACKUP_API_PASSWORD", "secret")))
                .isInstanceOf(CliException.class)
                .hasMessageContaining("HTTP is allowed only for loopback");
    }

    @Test
    void streamsAnArtifactAndOnlyReplacesTheDestinationAfterSuccess() throws Exception {
        byte[] artifact = new byte[2 * 1024 * 1024];
        Arrays.fill(artifact, (byte) 0x5a);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/backups/good/artifact", exchange -> {
            exchange.sendResponseHeaders(200, artifact.length);
            exchange.getResponseBody().write(artifact);
            exchange.close();
        });
        server.createContext("/api/v1/backups/missing/artifact", exchange -> {
            byte[] response = "{\"ok\":false,\"error\":{\"message\":\"artifact missing\"}}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(404, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        ApiClient client = new ApiClient(arguments(baseUrl()), new ObjectMapper(),
                Map.of("DBBACKUP_API_PASSWORD", "secret"));
        Path destination = temporaryDirectory.resolve("backup.bin");

        client.download("api/v1/backups/good/artifact", destination);
        assertThat(Files.size(destination)).isEqualTo(artifact.length);
        assertThat(Files.readAllBytes(destination)).containsOnly((byte) 0x5a);

        assertThatThrownBy(() -> client.download("api/v1/backups/missing/artifact", destination))
                .isInstanceOf(CliException.class).hasMessage("artifact missing");
        assertThat(Files.size(destination)).isEqualTo(artifact.length);
    }

    private String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private static CliArguments arguments(String url) {
        return CliArguments.parse(new String[] {
                "target", "list", "--server", url, "--username", "operator"
        }, Map.of());
    }
}
