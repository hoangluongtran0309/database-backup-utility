package com.hoangluongtran0309.dbbackup.cli;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

public final class DbBackupCli {
    private static final Set<String> CONTROL_OPTIONS = Set.of(
            "id", "targetId", "file", "page", "pageSize", "confirmBackups", "subscription", "secret");
    private static final Set<String> SECRET_FIELDS = Set.of(
            "password", "botToken", "webhookUrl", "secretAccessKey", "serviceAccountJson", "accountKey");

    private DbBackupCli() { }

    public static void main(String[] args) {
        int code;
        Map<String, String> environment = System.getenv();
        try {
            code = run(args, environment);
        } catch (CliException e) {
            printError(args, environment, e);
            code = e.exitCode();
        } catch (RuntimeException e) {
            printError(args, environment, new CliException(70, "Unexpected CLI failure: " + e.getMessage()));
            code = 70;
        }
        System.exit(code);
    }

    private static void printError(String[] source, Map<String, String> environment, CliException error) {
        if (!jsonRequested(source, environment)) {
            System.err.println(error.getMessage());
            return;
        }
        try {
            ObjectMapper json = new ObjectMapper();
            JsonNode envelope = error.envelope();
            if (envelope == null) {
                var root = json.createObjectNode().put("ok", false).putNull("data");
                var details = root.putObject("error");
                details.put("code", "CLI_ERROR").put("message", error.getMessage()).putNull("field");
                envelope = root;
            }
            System.err.println(json.writerWithDefaultPrettyPrinter().writeValueAsString(envelope));
        } catch (java.io.IOException impossible) {
            System.err.println(error.getMessage());
        }
    }

    private static boolean jsonRequested(String[] source, Map<String, String> environment) {
        boolean requested = "json".equalsIgnoreCase(environment.get("DBBACKUP_OUTPUT"));
        for (int i = 0; i < source.length; i++) {
            if (source[i].equals("--output") && i + 1 < source.length) requested = "json".equalsIgnoreCase(source[++i]);
            else if (source[i].startsWith("--output=")) requested = "json".equalsIgnoreCase(source[i].substring(9));
        }
        return requested;
    }

    static int run(String[] source, Map<String, String> environment) {
        if (source.length == 0 || source.length == 1 && Set.of("help", "--help", "-h").contains(source[0])) {
            System.out.println(help());
            return 0;
        }
        CliArguments args = CliArguments.parse(source, environment);
        ObjectMapper json = new ObjectMapper().registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        ApiClient api = new ApiClient(args, json, environment);
        Route route = route(args, environment);
        if (route.download()) {
            Path destination = Path.of(args.required("file"));
            api.download(route.path(), destination);
            printSuccess(args, json, json.valueToTree(Map.of("file", destination.toString())));
            return 0;
        }
        JsonNode envelope = api.request(route.method(), route.path(), route.body());
        JsonNode data = envelope.path("data");
        if (route.pollPath() != null && args.waitForCompletion()) {
            String id = data.path(route.idField()).asText();
            data = poll(api, route.pollPath().formatted(id));
            envelope = json.createObjectNode().put("ok", true).set("data", data);
            if (isFailed(data)) {
                print(args, json, envelope, data);
                return 5;
            }
        }
        print(args, json, envelope, data);
        return 0;
    }

    private static Route route(CliArguments args, Map<String, String> environment) {
        String id = args.optional("id");
        String targetId = args.optional("targetId");
        return switch (args.group() + " " + args.action()) {
            case "target list" -> get("api/v1/targets");
            case "target show" -> get("api/v1/targets/" + required(id, "--id"));
            case "target add" -> body("POST", "api/v1/targets", body(args, environment));
            case "target update" -> body("PUT", "api/v1/targets/" + required(id, "--id"), body(args, environment));
            case "target delete" -> new Route("DELETE", "api/v1/targets/" + required(id, "--id")
                    + "?confirmBackups=" + Boolean.parseBoolean(args.optional("confirmBackups")), null, null, null, false);
            case "target test" -> body("POST", "api/v1/targets/" + required(id, "--id") + "/test", null);
            case "storage list" -> get("api/v1/storage-profiles");
            case "storage show" -> get("api/v1/storage-profiles/" + required(id, "--id"));
            case "storage add" -> body("POST", "api/v1/storage-profiles", body(args, environment));
            case "storage update" -> body("PUT", "api/v1/storage-profiles/" + required(id, "--id"), body(args, environment));
            case "storage delete" -> body("DELETE", "api/v1/storage-profiles/" + required(id, "--id"), null);
            case "storage test" -> body("POST", "api/v1/storage-profiles/" + required(id, "--id") + "/test", null);
            case "notification list" -> get("api/v1/notification-channels");
            case "notification show" -> get("api/v1/notification-channels/" + required(id, "--id"));
            case "notification add" -> body("POST", "api/v1/notification-channels", body(args, environment));
            case "notification update" -> body("PUT", "api/v1/notification-channels/" + required(id, "--id"), body(args, environment));
            case "notification delete" -> body("DELETE", "api/v1/notification-channels/" + required(id, "--id"), null);
            case "notification test" -> body("POST", "api/v1/notification-channels/" + required(id, "--id") + "/test", null);
            case "subscription show" -> get("api/v1/targets/" + required(targetId, "--target-id") + "/notifications");
            case "subscription set" -> body("PUT", "api/v1/targets/" + required(targetId, "--target-id")
                    + "/notifications", subscriptionBody(args));
            case "schedule list" -> get("api/v1/schedules");
            case "schedule show" -> get("api/v1/schedules/" + required(id, "--id"));
            case "schedule add" -> body("POST", "api/v1/schedules", body(args, environment));
            case "schedule update" -> body("PUT", "api/v1/schedules/" + required(id, "--id"), body(args, environment));
            case "schedule delete" -> body("DELETE", "api/v1/schedules/" + required(id, "--id"), null);
            case "retention list" -> get("api/v1/retention");
            case "retention show" -> get("api/v1/retention/" + required(targetId, "--target-id"));
            case "retention set" -> body("PUT", "api/v1/retention/" + required(targetId, "--target-id"), body(args, environment));
            case "retention disable" -> body("DELETE", "api/v1/retention/" + required(targetId, "--target-id"), null);
            case "backup list" -> get("api/v1/backups?page=" + value(args, "page", "1")
                    + "&pageSize=" + value(args, "pageSize", "20"));
            case "backup show" -> get("api/v1/backups/" + required(id, "--id"));
            case "backup run" -> async("POST", "api/v1/targets/" + required(targetId, "--target-id")
                    + "/backups", null, "executionId", "api/v1/backups/%s");
            case "backup verify" -> body("POST", "api/v1/backups/" + required(id, "--id") + "/checksum", null);
            case "backup test-restore" -> async("POST", "api/v1/backups/" + required(id, "--id")
                    + "/verifications", null, "verificationId", "api/v1/verifications/%s");
            case "backup delete" -> body("DELETE", "api/v1/backups/" + required(id, "--id"), null);
            case "backup download" -> new Route("GET", "api/v1/backups/" + required(id, "--id")
                    + "/artifact", null, null, null, true);
            case "restore list" -> get("api/v1/restores?page=" + value(args, "page", "1")
                    + "&pageSize=" + value(args, "pageSize", "20"));
            case "restore show" -> get("api/v1/restores/" + required(id, "--id"));
            case "restore run" -> async("POST", "api/v1/restores", body(args, environment), "restoreId", "api/v1/restores/%s");
            default -> throw new CliException(2, "Unknown command '" + args.group() + " " + args.action() + "'");
        };
    }

    private static JsonNode poll(ApiClient api, String path) {
        long deadline = System.nanoTime() + Duration.ofHours(24).toNanos();
        while (System.nanoTime() < deadline) {
            JsonNode data = api.request("GET", path, null).path("data");
            JsonNode execution = operation(data);
            if (!"RUNNING".equals(execution.path("status").asText())) return data;
            try { Thread.sleep(1000); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new CliException(70, "Interrupted while waiting"); }
        }
        throw new CliException(5, "Timed out waiting for the operation");
    }

    private static boolean isFailed(JsonNode data) {
        return "FAILED".equals(operation(data).path("status").asText());
    }

    private static JsonNode operation(JsonNode data) {
        return data.has("execution") ? data.path("execution") : data;
    }

    static Map<String, Object> body(CliArguments args, Map<String, String> environment) {
        Map<String, Object> body = new LinkedHashMap<>();
        args.options().forEach((name, values) -> {
            if (!CONTROL_OPTIONS.contains(name)) {
                if (SECRET_FIELDS.contains(name)) {
                    throw new CliException(2, "--" + toKebab(name)
                            + " would expose a secret in the process list; use --secret " + name + "=ENV_VAR");
                }
                body.put(name, values.size() == 1 ? typed(values.getFirst()) : values.stream().map(DbBackupCli::typed).toList());
            }
        });
        for (String binding : args.values("secret")) {
            String[] parts = binding.split("=", 2);
            if (parts.length != 2 || !SECRET_FIELDS.contains(parts[0]) || parts[1].isBlank()) {
                throw new CliException(2, "--secret must be a supported FIELD=ENV_VAR binding");
            }
            String value = environment.get(parts[1]);
            if (value == null) throw new CliException(2, "Environment variable " + parts[1] + " is not set");
            body.put(parts[0], value);
        }
        return body;
    }

    private static String toKebab(String value) {
        return value.replaceAll("([a-z])([A-Z])", "$1-$2").toLowerCase(java.util.Locale.ROOT);
    }

    private static Map<String, Object> subscriptionBody(CliArguments args) {
        List<Map<String, Object>> subscriptions = new ArrayList<>();
        for (String value : args.values("subscription")) {
            String[] parts = value.split(":", 2);
            if (parts.length != 2 || parts[1].isBlank()) {
                throw new CliException(2, "--subscription must be CHANNEL_UUID:EVENT,EVENT");
            }
            subscriptions.add(Map.of("channelId", parts[0], "events", List.of(parts[1].split(","))));
        }
        return Map.of("subscriptions", subscriptions);
    }

    private static Object typed(String value) {
        if (value.equalsIgnoreCase("true") || value.equalsIgnoreCase("false")) return Boolean.valueOf(value);
        if (value.matches("-?\\d+")) {
            try { return Integer.valueOf(value); } catch (NumberFormatException ignored) { }
        }
        return value;
    }

    private static void print(CliArguments args, ObjectMapper json, JsonNode envelope, JsonNode data) {
        try {
            System.out.println(json.writerWithDefaultPrettyPrinter()
                    .writeValueAsString(args.output().equals("json") ? envelope : data));
        } catch (java.io.IOException e) {
            throw new CliException(70, "Could not render output: " + e.getMessage());
        }
    }

    private static void printSuccess(CliArguments args, ObjectMapper json, JsonNode data) {
        var envelope = json.createObjectNode().put("ok", true).set("data", data);
        print(args, json, envelope, data);
    }

    private static String required(String value, String option) {
        if (value == null || value.isBlank()) throw new CliException(2, option + " is required");
        return value;
    }

    private static String value(CliArguments args, String name, String fallback) {
        String value = args.optional(name);
        return value == null ? fallback : value;
    }

    private static Route get(String path) { return new Route("GET", path, null, null, null, false); }
    private static Route body(String method, String path, Object body) { return new Route(method, path, body, null, null, false); }
    private static Route async(String method, String path, Object body, String id, String poll) {
        return new Route(method, path, body, id, poll, false);
    }

    private static String help() {
        return """
                dbbackup — operator CLI for database-backup-utility

                Usage: dbbackup <group> <action> [options]

                Groups and actions:
                  target       list show add update delete test
                  storage      list show add update delete test
                  notification list show add update delete test
                  subscription show set
                  schedule     list show add update delete
                  retention    list show set disable
                  backup       list show run verify test-restore download delete
                  restore      list show run

                Global options:
                  --server URL          API base URL (DBBACKUP_API_URL; default http://localhost:8080)
                  --username NAME       Operator username (DBBACKUP_API_USERNAME)
                  --password-file FILE  Read the operator password from a private file
                  --password-stdin      Read the operator password from standard input
                  --output text|json    Output format (DBBACKUP_OUTPUT; default text)
                  --allow-http          Permit clear-text HTTP to a non-loopback server
                  --no-wait             Return after an asynchronous job is accepted

                Secrets may also come from DBBACKUP_API_PASSWORD. Resource fields use kebab-case
                options matching the API field names, for example --target-id and --verify-after-backup.
                Bind resource credentials without placing them in argv using --secret FIELD=ENV_VAR.
                Subscription values repeat as --subscription CHANNEL_UUID:EVENT,EVENT.
                """;
    }

    private record Route(String method, String path, Object body, String idField, String pollPath, boolean download) { }
}
