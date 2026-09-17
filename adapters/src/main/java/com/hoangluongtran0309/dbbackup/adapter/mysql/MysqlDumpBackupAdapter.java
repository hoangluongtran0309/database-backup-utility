package com.hoangluongtran0309.dbbackup.adapter.mysql;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPOutputStream;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.hoangluongtran0309.dbbackup.adapter.process.ProcessRunner;
import com.hoangluongtran0309.dbbackup.core.exception.BackupFailedException;
import com.hoangluongtran0309.dbbackup.core.model.DatabaseConnection;
import com.hoangluongtran0309.dbbackup.core.model.DatabaseEngine;
import com.hoangluongtran0309.dbbackup.core.port.LogicalBackupPort;

/**
 * Produces a logical dump by running {@code mysqldump}.
 *
 * <p>The dump is read from the child's stdout and gzipped straight to disk as
 * it arrives, so it is never held in memory whatever its size. stderr is
 * drained concurrently by {@link ProcessRunner}, which is what keeps a chatty
 * warning from filling its pipe and stalling the child.
 */
@Component
class MysqlDumpBackupAdapter implements LogicalBackupPort {

    /**
     * Bigger than {@link GZIPOutputStream}'s 512-byte default. A dump is one
     * long sequential write and this is the whole cost of not doing it in
     * half-kilobyte pieces.
     */
    private static final int GZIP_BUFFER_BYTES = 64 * 1024;

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
    public DatabaseEngine engine() {
        return DatabaseEngine.MYSQL;
    }

    @Override
    public String artifactSuffix() {
        return ".sql.gz";
    }

    @Override
    public long dumpTo(DatabaseConnection connection, Path destination) {
        ProcessRunner.Result result;

        // The gzip trailer is written when this stream closes, so the file is
        // only a valid archive once the block has exited. Everything that
        // inspects or deletes it therefore happens afterwards.
        try (OutputStream out = new GZIPOutputStream(
                new BufferedOutputStream(Files.newOutputStream(destination)), GZIP_BUFFER_BYTES)) {

            result = processRunner.runStreaming(
                    command(connection),
                    Map.of("MYSQL_PWD", connection.password()),
                    timeout,
                    out);

        } catch (IOException e) {
            throw failed(destination, "Could not write '%s': %s".formatted(destination, e.getMessage()));
        } catch (ProcessRunner.ProcessFailedException e) {
            throw failed(destination, e.getMessage());
        }

        if (!result.succeeded()) {
            throw failed(destination, "mysqldump exited with %d: %s"
                    .formatted(result.exitCode(), result.errorOutput()));
        }
        return sizeOf(destination);
    }

    List<String> command(DatabaseConnection connection) {
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
                // No --result-file: the dump goes to stdout so it can be
                // compressed on the way past. See ADR-006.
                // Positional, not --databases: that keeps CREATE DATABASE and
                // USE out of the dump, so it can be restored into a schema
                // with a different name.
                connection.database());
    }

    /**
     * A partial dump is worse than no dump: it looks like a backup and restores
     * as silent data loss. With gzip it is worse still — a truncated archive
     * has no trailer, so it fails to decompress at exactly the moment someone
     * needs it. The file goes before the exception does.
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
