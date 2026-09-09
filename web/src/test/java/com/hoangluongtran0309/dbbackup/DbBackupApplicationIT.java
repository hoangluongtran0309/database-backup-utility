package com.hoangluongtran0309.dbbackup;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.Executor;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import com.hoangluongtran0309.dbbackup.application.backup.RunBackupService;
import com.hoangluongtran0309.dbbackup.core.port.MysqlConnectionTestPort;
import com.hoangluongtran0309.dbbackup.core.port.MysqlLogicalBackupPort;
import com.hoangluongtran0309.dbbackup.core.port.StoragePort;

/**
 * Boots the whole application against a real PostgreSQL.
 *
 * <p>Every other test in this module is a {@code @WebMvcTest} slice, and a
 * slice loads neither the configuration classes nor the wiring between them.
 * That gap let a bean cycle between {@code BackupExecutorConfig} and
 * {@code RunBackupService} reach a manual run with all 127 tests green. This
 * class closes it: if the context cannot be built, this fails.
 */
@SpringBootTest
@Testcontainers
class DbBackupApplicationIT {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("dbbackup.encryption.key", () -> "ZGJiYWNrdXAtaW50ZWdyYXRpb24tdGVzdC1rZXktMzI=");
        registry.add("dbbackup.storage.local.root",
                () -> System.getProperty("java.io.tmpdir") + "/dbbackup-context-it");
    }

    @Autowired
    private ApplicationContext context;

    @Test
    void theApplicationContextStarts() {
        assertThat(context).isNotNull();
    }

    /** Each port is satisfied by exactly one adapter, and the executor exists. */
    @Test
    void everyPortIsWiredToAnAdapter() {
        assertThat(context.getBeanNamesForType(MysqlLogicalBackupPort.class)).hasSize(1);
        assertThat(context.getBeanNamesForType(MysqlConnectionTestPort.class)).hasSize(1);
        assertThat(context.getBeanNamesForType(StoragePort.class)).hasSize(1);
        assertThat(context.getBean(RunBackupService.class)).isNotNull();
        assertThat(context.getBean(Executor.class)).isNotNull();
    }

    /** Flyway ran, and the JPA mappings still match the schema it produced. */
    @Test
    void flywayAndHibernateAgreeOnTheSchema() {
        // ddl-auto=validate means reaching this point at all is the assertion;
        // a drifted mapping would have failed the context above.
        assertThat(context.containsBean("flywayInitializer")).isTrue();
    }
}
