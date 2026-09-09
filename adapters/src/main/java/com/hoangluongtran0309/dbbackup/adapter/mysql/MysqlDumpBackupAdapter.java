package com.hoangluongtran0309.dbbackup.adapter.mysql;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.hoangluongtran0309.dbbackup.adapter.process.ProcessRunner;
import com.hoangluongtran0309.dbbackup.core.exception.BackupFailedException;
import com.hoangluongtran0309.dbbackup.core.model.MysqlConnection;
import com.hoangluongtran0309.dbbackup.core.port.MysqlLogicalBackupPort;

/**
 * Produces a logical dump by running {@code mysqldump}.
 *
 * <p>{@code --result-file} is used rather than capturing stdout: the dump never
 * passes through the JVM, so a multi-gigabyte schema costs no heap. stderr is
 * still drained concurrently by {@link ProcessRunner}, which is what keeps a
 * chatty warning from filling its pipe and stalling the child.
 */
@Component
class MysqlDumpBackupAdapter implements MysqlLogicalBackupPort {

    private final ProcessRunner processRunner;
    private final Path binary;
    private final Duration timeout;

    MysqlDumpBackupAdapter(
            ProcessRunner processRunner,
            @Value("${dbbackup.mysql.dump-path}") Path binary,
            @Value("${dbbackup.backup.timeout}") Duration timeout) {

        this.processRunner = processRunner;
        this.binary = MysqlBinaries.require(binary, "dbbackup.mysql.dump-path");
        this.timeout = timeout;
    }

    @Override
    public long dumpTo(MysqlConnection connection, Path destination) {
        ProcessRunner.Result result;
        try {
            result = processRunner.run(
                    command(connection, destination),
                    Map.of("MYSQL_PWD", connection.password()),
                    timeout);
        } catch (ProcessRunner.ProcessFailedException e) {
            throw failed(destination, e.getMessage());
        }

        if (!result.succeeded()) {
            throw failed(destination, "mysqldump exited with %d: %s"
                    .formatted(result.exitCode(), result.errorOutput()));
        }
        return sizeOf(destination);
    }

    List<String> command(MysqlConnection connection, Path destination) {
        return List.of(
                binary.toString(),
                // See MysqlClient: a literal "localhost" makes the client use a
                // Unix socket and ignore --port entirely.
                "--host=" + MysqlClient.tcpHost(connection.host()),
                "--port=" + connection.port(),
                "--user=" + connection.username(),
                // No --connect-timeout here: unlike the mysql client, mysqldump
                // does not accept it and exits 7 with "unknown variable". The
                // ProcessRunner timeout is the backstop for a dump that hangs.
                // One consistent snapshot from InnoDB without locking the
                // tables against the application that is using them.
                "--single-transaction",
                "--routines",
                "--triggers",
                "--events",
                // Otherwise the dump begins with SET @@GLOBAL.GTID_PURGED,
                // which restore cannot execute without SUPER.
                "--set-gtid-purged=OFF",
                "--result-file=" + destination,
                // Positional, not --databases: that keeps CREATE DATABASE and
                // USE out of the dump, so it can be restored into a schema
                // with a different name.
                connection.database());
    }

    /**
     * A partial dump is worse than no dump: it looks like a backup and restores
     * as silent data loss. The file goes before the exception does.
     */
    private BackupFailedException failed(Path destination, String message) {
        try {
            Files.deleteIfExists(destination);
        } catch (IOException e) {
            return new BackupFailedException(
                    "%s (and the partial file '%s' could not be removed: %s)"
                            .formatted(message, destination, e.getMessage()));
        }
        return new BackupFailedException(message);
    }

    private long sizeOf(Path destination) {
        try {
            return Files.size(destination);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
