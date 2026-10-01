package com.hoangluongtran0309.dbbackup.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;

class DbBackupCliOutputTest {
    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) server.stop(0);
    }

    @Test
    void defaultOutputUsesTheTextRenderer() throws Exception {
        serveTargets();

        String output = run("target", "list", "--server", baseUrl(), "--username", "operator");

        assertThat(output).contains("ID", "NAME", "production", "POSTGRESQL")
                .doesNotContain("{", "}", "[", "]");
    }

    @Test
    void jsonOutputKeepsTheCompleteEnvelope() throws Exception {
        serveTargets();

        String output = run("target", "list", "--server", baseUrl(), "--username", "operator",
                "--output", "json");

        var root = new ObjectMapper().readTree(output);
        assertThat(root.path("ok").asBoolean()).isTrue();
        assertThat(root.path("data").get(0).path("name").asText()).isEqualTo("production");
        assertThat(root.path("error").isNull()).isTrue();
    }

    private void serveTargets() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/targets", exchange -> {
            byte[] response = ("{\"ok\":true,\"data\":[{\"id\":\"1\",\"name\":\"production\","
                    + "\"engine\":\"POSTGRESQL\"}],\"error\":null}").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
    }

    private String run(String... arguments) {
        PrintStream original = System.out;
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(bytes, true, StandardCharsets.UTF_8));
            assertThat(DbBackupCli.run(arguments, Map.of("DBBACKUP_API_PASSWORD", "secret"))).isZero();
            return bytes.toString(StandardCharsets.UTF_8);
        } finally {
            System.setOut(original);
        }
    }

    private String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }
}
