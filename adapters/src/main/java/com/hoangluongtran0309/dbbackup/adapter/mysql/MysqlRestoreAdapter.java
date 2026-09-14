package com.hoangluongtran0309.dbbackup.adapter.mysql;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
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
 * <p>The archive is decompressed as it is fed to the client's stdin, so a
 * restore needs no disk beyond the archive itself (ADR-016). It is read twice:
 * once to the end, discarding what it decompresses to, before the client is
 * started; then again into the client. The first read is what finds a truncated
 * or corrupt archive while the target is still untouched — once the client has
 * started, every statement before the bad bytes has already been applied.
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
        checkArchive(artifact);

        ProcessRunner.Result result;
        InputStream sql = open(artifact);
        try {
            result = processRunner.runFeeding(
                    command(connection),
                    Map.of("MYSQL_PWD", connection.password()),
                    timeout,
                    sql);
        } catch (ProcessRunner.ProcessFailedException e) {
            throw new RestoreFailedException(e.getMessage(), e);
        } finally {
            closeQuietly(sql);
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

    /**
     * Decompresses the whole archive and throws the result away. Costs a read
     * of the file and the CPU to inflate it; buys the guarantee that the client
     * is never started on an archive that stops halfway.
     */
    private static void checkArchive(Path artifact) {
        try (InputStream in = open(artifact)) {
            in.transferTo(OutputStream.nullOutputStream());
        } catch (IOException e) {
            throw unreadable(artifact, e);
        }
    }

    /** Reads the gzip header, so a file that is not an archive at all fails here. */
    private static InputStream open(Path artifact) {
        try {
            return new GZIPInputStream(Files.newInputStream(artifact));
        } catch (IOException e) {
            throw unreadable(artifact, e);
        }
    }

    private static void closeQuietly(InputStream in) {
        try {
            in.close();
        } catch (IOException e) {
            // A file that was only read: the restore's own outcome is the
            // thing worth reporting, and it is already known.
            org.slf4j.LoggerFactory.getLogger(MysqlRestoreAdapter.class)
                    .warn("Could not close the backup artifact after restoring from it", e);
        }
    }

    private static RestoreFailedException unreadable(Path artifact, IOException e) {
        return new RestoreFailedException(
                "Could not read the backup artifact '%s': %s. Nothing was restored."
                        .formatted(artifact, e.getMessage()), e);
    }
}
