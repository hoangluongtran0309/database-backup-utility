package com.hoangluongtran0309.dbbackup.adapter.mariadb;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.hoangluongtran0309.dbbackup.adapter.process.ProcessRunner;
import com.hoangluongtran0309.dbbackup.core.model.DatabaseConnection;

/** Assembles invocations of MariaDB's own {@code mariadb} client. */
@Component
class MariaDbClient {

    private final ProcessRunner processRunner;
    private final Path binary;
    private final Duration connectTimeout;

    MariaDbClient(
            ProcessRunner processRunner,
            @Value("${dbbackup.mariadb.client-path}") Path binary,
            @Value("${dbbackup.mariadb.connect-timeout}") Duration connectTimeout) {

        this.processRunner = processRunner;
        this.binary = MariaDbBinaries.require(binary, "dbbackup.mariadb.client-path");
        this.connectTimeout = connectTimeout;
    }

    ProcessRunner.Result execute(DatabaseConnection connection, String sql) {
        return processRunner.run(
                List.of(
                        binary.toString(),
                        "--protocol=TCP",
                        "--host=" + connection.host(),
                        "--port=" + connection.port(),
                        "--user=" + connection.username(),
                        "--connect-timeout=" + connectTimeout.toSeconds(),
                        "--batch",
                        "--skip-column-names",
                        "--execute=" + sql,
                        connection.database()),
                environment(connection),
                connectTimeout.plusSeconds(5));
    }

    static Map<String, String> environment(DatabaseConnection connection) {
        // MariaDB documents MYSQL_PWD for its clients. It is scoped to this
        // child process and, unlike --password, is absent from ps output.
        return Map.of("MYSQL_PWD", connection.password());
    }
}
