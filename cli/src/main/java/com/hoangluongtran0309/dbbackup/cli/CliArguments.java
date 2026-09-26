package com.hoangluongtran0309.dbbackup.cli;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

record CliArguments(
        String group,
        String action,
        Map<String, List<String>> options,
        String server,
        String username,
        String output,
        boolean allowHttp,
        boolean waitForCompletion,
        Path passwordFile,
        boolean passwordStdin) {

    static CliArguments parse(String[] source, Map<String, String> environment) {
        List<String> command = new ArrayList<>();
        Map<String, List<String>> options = new LinkedHashMap<>();
        String server = environment.getOrDefault("DBBACKUP_API_URL", "http://localhost:8080");
        String username = environment.getOrDefault("DBBACKUP_API_USERNAME",
                environment.getOrDefault("OPERATOR_USERNAME", "admin"));
        String output = environment.getOrDefault("DBBACKUP_OUTPUT", "text");
        boolean allowHttp = false;
        boolean wait = true;
        Path passwordFile = null;
        boolean passwordStdin = false;

        for (int i = 0; i < source.length; i++) {
            String value = source[i];
            if (!value.startsWith("--")) {
                command.add(value);
                continue;
            }
            String name = value.substring(2);
            String inline = null;
            int equals = name.indexOf('=');
            if (equals >= 0) {
                inline = name.substring(equals + 1);
                name = name.substring(0, equals);
            }
            switch (name) {
                case "server" -> {
                    String resolved = inline != null ? inline : requiredValue(source, ++i, null, "--server");
                    server = resolved;
                }
                case "username" -> {
                    String resolved = inline != null ? inline : requiredValue(source, ++i, null, "--username");
                    username = resolved;
                }
                case "output" -> {
                    String resolved = inline != null ? inline : requiredValue(source, ++i, null, "--output");
                    output = resolved;
                }
                case "allow-http" -> allowHttp = true;
                case "no-wait" -> wait = false;
                case "password-file" -> {
                    String resolved = inline != null ? inline : requiredValue(source, ++i, null, "--password-file");
                    passwordFile = Path.of(resolved);
                }
                case "password-stdin" -> passwordStdin = true;
                default -> {
                    String optionValue;
                    if (inline != null) {
                        optionValue = inline;
                    } else if (i + 1 < source.length && !source[i + 1].startsWith("--")) {
                        optionValue = source[++i];
                    } else {
                        optionValue = "true";
                    }
                    options.computeIfAbsent(toCamel(name), ignored -> new ArrayList<>()).add(optionValue);
                }
            }
        }
        if (command.size() != 2) {
            throw new CliException(2, "Expected a command group and action; run 'dbbackup help'");
        }
        output = output.toLowerCase(Locale.ROOT);
        if (!output.equals("text") && !output.equals("json")) {
            throw new CliException(2, "--output must be text or json");
        }
        if (passwordFile != null && passwordStdin) {
            throw new CliException(2, "Choose only one of --password-file or --password-stdin");
        }
        return new CliArguments(command.get(0), command.get(1), Map.copyOf(options), server, username,
                output, allowHttp, wait, passwordFile, passwordStdin);
    }

    String required(String name) {
        List<String> values = options.get(name);
        if (values == null || values.isEmpty() || values.getFirst().isBlank()) {
            throw new CliException(2, "--" + toKebab(name) + " is required");
        }
        return values.getFirst();
    }

    String optional(String name) {
        List<String> values = options.get(name);
        return values == null || values.isEmpty() ? null : values.getFirst();
    }

    List<String> values(String name) {
        return options.getOrDefault(name, List.of());
    }

    private static String requiredValue(String[] source, int index, String inline, String option) {
        if (inline != null) return inline;
        if (index >= source.length) throw new CliException(2, option + " requires a value");
        return source[index];
    }

    private static String toCamel(String value) {
        StringBuilder result = new StringBuilder();
        boolean upper = false;
        for (char character : value.toCharArray()) {
            if (character == '-') { upper = true; continue; }
            result.append(upper ? Character.toUpperCase(character) : character);
            upper = false;
        }
        return result.toString();
    }

    private static String toKebab(String value) {
        return value.replaceAll("([a-z])([A-Z])", "$1-$2").toLowerCase(Locale.ROOT);
    }
}
