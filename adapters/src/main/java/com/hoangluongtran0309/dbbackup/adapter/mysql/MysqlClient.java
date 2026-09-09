package com.hoangluongtran0309.dbbackup.adapter.mysql;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.hoangluongtran0309.dbbackup.adapter.process.ProcessRunner;
import com.hoangluongtran0309.dbbackup.core.model.MysqlConnection;

/**
 * Assembles and runs {@code mysql} command lines.
 *
 * <p>Shared by everything that shells out to the MySQL client tools, so that
 * the two rules below are stated once rather than repeated at each call site.
 */
@Component
public class MysqlClient {

    /**
     * The MySQL client treats the literal string {@code localhost} as a request
     * for a Unix socket and <em>silently ignores {@code --port}</em>. A target
     * on 127.0.0.1:3307 would then be probed on the default socket instead —
     * connecting to the wrong server, or to none, with a confusing error.
     * Rewriting the host is what makes the port mean what it says.
     */
    private static final String LOCALHOST = "localhost";
    private static final String LOOPBACK = "127.0.0.1";

    private final ProcessRunner processRunner;
    private final Path binary;
    private final Duration connectTimeout;

    MysqlClient(
            ProcessRunner processRunner,
            @Value("${dbbackup.mysql.client-path}") Path binary,
            @Value("${dbbackup.mysql.connect-timeout}") Duration connectTimeout) {

        this.processRunner = processRunner;
        this.connectTimeout = connectTimeout;
        this.binary = binary;

        // Fail at startup, not at the first backup. This tool cannot do
        // anything useful without the client binaries, so a wrong path is
        // worth refusing to start over rather than reporting later as a
        // mysterious connection failure.
        if (!Files.isExecutable(binary)) {
            throw new IllegalStateException(
                    "dbbackup.mysql.client-path points at '%s', which is not an executable file"
                            .formatted(binary));
        }
    }

    /**
     * Runs one statement and returns what the client reported.
     *
     * <p>The password reaches the child through {@code MYSQL_PWD} and never
     * appears in {@code command}, so it is not visible in {@code ps}.
     */
    public ProcessRunner.Result execute(MysqlConnection connection, String sql) {
        List<String> command = new ArrayList<>(baseArguments(connection));
        command.add("--batch");
        command.add("--skip-column-names");
        command.add("--execute=" + sql);
        // Positional, and last: the schema to run against.
        command.add(connection.database());

        return processRunner.run(
                command,
                Map.of("MYSQL_PWD", connection.password()),
                // The client gives up first; this is only a backstop for a
                // child that ignores its own timeout.
                connectTimeout.plusSeconds(5));
    }

    private List<String> baseArguments(MysqlConnection connection) {
        return List.of(
                binary.toString(),
                "--host=" + tcpHost(connection.host()),
                "--port=" + connection.port(),
                "--user=" + connection.username(),
                "--connect-timeout=" + connectTimeout.toSeconds());
    }

    /** See {@link #LOCALHOST}. */
    static String tcpHost(String host) {
        return LOCALHOST.equalsIgnoreCase(host) ? LOOPBACK : host;
    }
}
