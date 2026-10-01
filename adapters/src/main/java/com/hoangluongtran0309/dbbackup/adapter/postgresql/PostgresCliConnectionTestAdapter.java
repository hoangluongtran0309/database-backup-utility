package com.hoangluongtran0309.dbbackup.adapter.postgresql;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.hoangluongtran0309.dbbackup.adapter.process.ProcessRunner;
import com.hoangluongtran0309.dbbackup.core.model.DatabaseConnection;
import com.hoangluongtran0309.dbbackup.core.model.DatabaseEngine;
import com.hoangluongtran0309.dbbackup.core.port.ConnectionTestPort;

/** Tests PostgreSQL through psql, the same client family the backup path uses. */
@Component
class PostgresCliConnectionTestAdapter implements ConnectionTestPort {

    private static final Pattern SERVER_VERSION_NUMBER = Pattern.compile("\\d+");
    private static final Pattern PG_DUMP_VERSION = Pattern.compile(
            "pg_dump \\(PostgreSQL\\) (\\d+)(?:\\.\\d+)*", Pattern.CASE_INSENSITIVE);

    private final ProcessRunner processRunner;
    private final Path psqlBinary;
    private final Path dumpBinary;
    private final Duration connectTimeout;

    PostgresCliConnectionTestAdapter(
            ProcessRunner processRunner,
            @Value("${dbbackup.postgresql.psql-path}") Path psqlBinary,
            @Value("${dbbackup.postgresql.dump-path}") Path dumpBinary,
            @Value("${dbbackup.postgresql.connect-timeout}") Duration connectTimeout) {
        this.processRunner = processRunner;
        this.psqlBinary = PostgresBinaries.require(psqlBinary, "dbbackup.postgresql.psql-path");
        this.dumpBinary = PostgresBinaries.require(dumpBinary, "dbbackup.postgresql.dump-path");
        this.connectTimeout = connectTimeout;
    }

    @Override
    public DatabaseEngine engine() {
        return DatabaseEngine.POSTGRESQL;
    }

    @Override
    public Result test(DatabaseConnection connection) {
        try {
            ProcessRunner.Result probe = processRunner.run(
                    List.of(
                            psqlBinary.toString(),
                            "--host=" + connection.host(),
                            "--port=" + connection.port(),
                            "--username=" + connection.username(),
                            "--dbname=" + connection.database(),
                            "--no-password",
                            "--tuples-only",
                            "--no-align",
                            "--command=SHOW server_version_num"),
                    environment(connection, connectTimeout),
                    connectTimeout.plusSeconds(5));
            if (!probe.succeeded()) {
                return Result.failed(probe.errorOutput());
            }

            Integer serverMajor = serverMajor(probe.stdout());
            if (serverMajor == null) {
                return Result.failed(
                        "Could not determine the PostgreSQL server version from psql output: %s"
                                .formatted(display(probe.stdout())));
            }

            ProcessRunner.Result version = processRunner.run(
                    List.of(dumpBinary.toString(), "--version"),
                    Map.of(),
                    connectTimeout.plusSeconds(5));
            if (!version.succeeded()) {
                return Result.failed(
                        "Could not read the configured pg_dump version from '%s': %s"
                                .formatted(dumpBinary, version.errorOutput()));
            }

            Integer dumpMajor = dumpMajor(version.stdout());
            if (dumpMajor == null) {
                return Result.failed(
                        "Could not determine the configured pg_dump version from '%s': %s"
                                .formatted(dumpBinary, display(version.stdout())));
            }
            if (serverMajor > dumpMajor) {
                return Result.failed(
                        ("PostgreSQL server major %d is newer than configured pg_dump major %d at '%s'; "
                                + "pg_dump %d cannot back up this server. "
                                + "Set PG_DUMP_PATH to pg_dump major %d or newer.")
                                .formatted(serverMajor, dumpMajor, dumpBinary, dumpMajor, serverMajor));
            }
            return Result.ok();
        } catch (ProcessRunner.ProcessFailedException e) {
            return Result.failed(e.getMessage());
        }
    }

    private static Integer serverMajor(String output) {
        String value = output.strip();
        if (!SERVER_VERSION_NUMBER.matcher(value).matches()) {
            return null;
        }
        try {
            long versionNumber = Long.parseLong(value);
            long major = versionNumber / 10_000;
            return major > 0 && major <= Integer.MAX_VALUE ? (int) major : null;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static Integer dumpMajor(String output) {
        Matcher matcher = PG_DUMP_VERSION.matcher(output.strip());
        if (!matcher.find()) {
            return null;
        }
        try {
            return Integer.valueOf(matcher.group(1));
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static String display(String output) {
        return output.isBlank() ? "<empty>" : "'" + output.strip() + "'";
    }

    static Map<String, String> environment(DatabaseConnection connection, Duration connectTimeout) {
        return Map.of(
                "PGPASSWORD", connection.password(),
                "PGCONNECT_TIMEOUT", Long.toString(connectTimeout.toSeconds()));
    }
}
