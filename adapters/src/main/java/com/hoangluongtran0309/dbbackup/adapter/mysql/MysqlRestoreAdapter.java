package com.hoangluongtran0309.dbbackup.adapter.mysql;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.zip.GZIPInputStream;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.hoangluongtran0309.dbbackup.adapter.process.ProcessRunner;
import com.hoangluongtran0309.dbbackup.core.exception.RestoreFailedException;
import com.hoangluongtran0309.dbbackup.core.model.MysqlConnection;
import com.hoangluongtran0309.dbbackup.core.port.MysqlLogicalRestorePort;

/**
 * Loads a gzipped dump into a schema by feeding it to the {@code mysql} client.
 *
 * <p>The artifact is decompressed to a temporary file first and the client's
 * stdin is redirected from it. Streaming the gunzip straight into the child's
 * stdin from a Java thread would work right up until the child wrote enough to
 * fill its own output pipe: it would block on us, we would block on it, and
 * neither would move again. A temporary file costs disk that the artifact's own
 * directory already has, and removes the possibility.
 */
@Component
class MysqlRestoreAdapter implements MysqlLogicalRestorePort {

    private final ProcessRunner processRunner;
    private final Path binary;
    private final Duration connectTimeout;
    private final Duration timeout;

    MysqlRestoreAdapter(
            ProcessRunner processRunner,
            @Value("${dbbackup.mysql.client-path}") Path binary,
            @Value("${dbbackup.mysql.connect-timeout}") Duration connectTimeout,
            @Value("${dbbackup.restore.timeout}") Duration timeout) {

        this.processRunner = processRunner;
        this.binary = MysqlBinaries.require(binary, "dbbackup.mysql.client-path");
        this.connectTimeout = connectTimeout;
        this.timeout = timeout;
    }

    @Override
    public void restore(MysqlConnection connection, Path artifact) {
        if (!Files.isReadable(artifact)) {
            throw new RestoreFailedException(
                    "The backup artifact '%s' is missing or unreadable".formatted(artifact));
        }

        // Beside the artifact, not in /tmp: an uncompressed dump can be many
        // times the archive, and this directory is the one already sized for
        // backups. A random suffix keeps two concurrent restores of the same
        // artifact apart.
        Path sql = artifact.resolveSibling(
                artifact.getFileName() + "." + UUID.randomUUID() + ".restore.sql");
        try {
            decompress(artifact, sql);
            run(connection, sql);
        } finally {
            deleteQuietly(sql);
        }
    }

    private void run(MysqlConnection connection, Path sql) {
        ProcessRunner.Result result;
        try {
            result = processRunner.runWithInput(
                    command(connection),
                    Map.of("MYSQL_PWD", connection.password()),
                    timeout,
                    sql);
        } catch (ProcessRunner.ProcessFailedException e) {
            throw new RestoreFailedException(e.getMessage(), e);
        }

        if (!result.succeeded()) {
            // A restore that stops halfway leaves the schema in a state nobody
            // chose. The client's own words are what say where it stopped.
            throw new RestoreFailedException(
                    "mysql exited with %d: %s".formatted(result.exitCode(), result.errorOutput()));
        }
    }

    List<String> command(MysqlConnection connection) {
        return List.of(
                binary.toString(),
                // See MysqlClient: a literal "localhost" makes the client use a
                // Unix socket and ignore --port entirely.
                "--host=" + MysqlClient.tcpHost(connection.host()),
                "--port=" + connection.port(),
                "--user=" + connection.username(),
                "--connect-timeout=" + connectTimeout.toSeconds(),
                // Stop at the first error instead of ploughing on and leaving a
                // schema that is part old and part new with no error to show.
                "--batch",
                connection.database());
    }

    private static void decompress(Path artifact, Path destination) {
        try (InputStream in = new GZIPInputStream(Files.newInputStream(artifact));
             OutputStream out = Files.newOutputStream(destination)) {
            in.transferTo(out);
        } catch (IOException e) {
            throw new RestoreFailedException(
                    "Could not read the backup artifact '%s': %s".formatted(artifact, e.getMessage()), e);
        }
    }

    private static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            // The restore's own outcome is the thing worth reporting; a
            // leftover temporary file is not worth masking it with.
            org.slf4j.LoggerFactory.getLogger(MysqlRestoreAdapter.class)
                    .warn("Could not remove the temporary file {}", path, e);
        }
    }
}
