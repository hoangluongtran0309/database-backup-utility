package com.hoangluongtran0309.dbbackup.adapter.oracle;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.hoangluongtran0309.dbbackup.adapter.process.ProcessRunner;
import com.hoangluongtran0309.dbbackup.core.model.DatabaseConnection;

final class OracleClient {

    private OracleClient() {
    }

    static ProcessRunner.Result run(
            ProcessRunner runner,
            Path binary,
            DatabaseConnection connection,
            List<String> parameters,
            Duration timeout,
            String... inputLines) {
        List<String> command = new ArrayList<>();
        command.add(binary.toString());
        // USERID must be Data Pump's first argument. TWO_TASK supplies the
        // remote service without forcing the password into argv.
        command.add(connection.username());
        command.addAll(parameters);
        return runner.runFeeding(
                List.copyOf(command), Map.of("TWO_TASK", networkService(connection)), timeout,
                new ByteArrayInputStream(input(connection.password(), inputLines)));
    }

    /** Sends a silent SQL*Plus script; the quoted password exists only on stdin. */
    static ProcessRunner.Result runSqlPlus(
            ProcessRunner runner,
            Path binary,
            DatabaseConnection connection,
            Duration timeout,
            String script) {
        String connect = "CONNECT %s/\"%s\"@%s".formatted(
                connection.username(), quoteSqlPlusPassword(connection.password()), networkService(connection));
        String input = connect + "\n" + script + "\n";
        return runner.runFeeding(
                List.of(binary.toString(), "-L", "-S", "/NOLOG"),
                Map.of(),
                timeout,
                new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)));
    }

    static ProcessRunner.Result attachAndKill(
            ProcessRunner runner,
            Path binary,
            DatabaseConnection connection,
            String jobName,
            Duration timeout) {
        List<String> command = List.of(binary.toString(), connection.username(), "ATTACH=" + jobName);
        return runner.runResponding(
                command,
                Map.of("TWO_TASK", networkService(connection)),
                timeout,
                List.of(
                        new ProcessRunner.PromptResponse("Password:", connection.password()),
                        new ProcessRunner.PromptResponse(">", "KILL_JOB"),
                        new ProcessRunner.PromptResponse("Are you sure", "YES")));
    }

    private static String networkService(DatabaseConnection connection) {
        return "//%s:%d/%s".formatted(
                connection.host(), connection.port(), connection.database());
    }

    private static String quoteSqlPlusPassword(String password) {
        if (password == null || password.indexOf('\n') >= 0 || password.indexOf('\r') >= 0) {
            throw new IllegalArgumentException("Oracle passwords must be present and contain no line breaks");
        }
        return password.replace("\"", "\"\"");
    }

    private static byte[] input(String password, String... lines) {
        if (password == null || password.indexOf('\n') >= 0 || password.indexOf('\r') >= 0) {
            throw new IllegalArgumentException("Oracle passwords must be present and contain no line breaks");
        }
        StringBuilder input = new StringBuilder(password).append('\n');
        for (String line : lines) {
            input.append(line).append('\n');
        }
        return input.toString().getBytes(StandardCharsets.UTF_8);
    }

}
