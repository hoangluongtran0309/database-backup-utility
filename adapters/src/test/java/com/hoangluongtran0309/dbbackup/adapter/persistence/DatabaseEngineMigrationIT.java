package com.hoangluongtran0309.dbbackup.adapter.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.UUID;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** Proves V7 upgrades real pre-engine data rather than only building a fresh schema. */
@Testcontainers
class DatabaseEngineMigrationIT {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

    @Test
    void backfillsMysqlWithoutChangingCredentialsOrHistory() throws Exception {
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .target(MigrationVersion.fromVersion("6"))
                .load()
                .migrate();

        UUID targetId = UUID.randomUUID();
        UUID backupId = UUID.randomUUID();
        UUID restoreId = UUID.randomUUID();
        String ciphertext = "ciphertext-that-must-survive";
        try (Connection connection = connection(); Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                    INSERT INTO database_targets
                        (id, name, host, port, database_name, username, password_enc, created_at)
                    VALUES ('%s', 'legacy mysql', 'db.internal', 3306, 'shop', 'backup', '%s', now())
                    """.formatted(targetId, ciphertext));
            statement.executeUpdate("""
                    INSERT INTO backup_executions
                        (id, target_id, status, started_at, finished_at, artifact_path, size_bytes, sha256)
                    VALUES ('%s', '%s', 'SUCCEEDED', now(), now(), '/backups/shop.sql.gz', 42,
                            'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa')
                    """.formatted(backupId, targetId));
            statement.executeUpdate("""
                    INSERT INTO restore_executions
                        (id, backup_execution_id, target_id, status, started_at, finished_at)
                    VALUES ('%s', '%s', '%s', 'SUCCEEDED', now(), now())
                    """.formatted(restoreId, backupId, targetId));
        }

        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .load()
                .migrate();

        try (Connection connection = connection(); Statement statement = connection.createStatement()) {
            assertThat(single(statement,
                    "SELECT engine FROM database_targets WHERE id = '" + targetId + "'"))
                    .isEqualTo("MYSQL");
            assertThat(single(statement,
                    "SELECT password_enc FROM database_targets WHERE id = '" + targetId + "'"))
                    .isEqualTo(ciphertext);
            assertThat(single(statement,
                    "SELECT count(*)::text FROM backup_executions WHERE id = '" + backupId + "'"))
                    .isEqualTo("1");
            assertThat(single(statement,
                    "SELECT count(*)::text FROM restore_executions WHERE id = '" + restoreId + "'"))
                    .isEqualTo("1");
            assertThat(single(statement,
                    "SELECT authentication_database FROM database_targets WHERE id = '" + targetId + "'"))
                    .isNull();

            UUID mongoId = UUID.randomUUID();
            statement.executeUpdate("""
                    INSERT INTO database_targets
                        (id, name, engine, host, port, database_name, username,
                         authentication_database, password_enc, created_at)
                    VALUES ('%s', 'mongo', 'MONGODB', 'mongo.internal', 27017,
                            'shop', 'backup', 'admin', 'sealed', now())
                    """.formatted(mongoId));
            assertThat(single(statement,
                    "SELECT authentication_database FROM database_targets WHERE id = '" + mongoId + "'"))
                    .isEqualTo("admin");
        }
    }

    private static Connection connection() throws Exception {
        return DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    private static String single(Statement statement, String sql) throws Exception {
        try (ResultSet result = statement.executeQuery(sql)) {
            result.next();
            return result.getString(1);
        }
    }
}
