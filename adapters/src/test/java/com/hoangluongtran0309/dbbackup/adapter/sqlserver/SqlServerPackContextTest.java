package com.hoangluongtran0309.dbbackup.adapter.sqlserver;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.convert.ApplicationConversionService;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import com.hoangluongtran0309.dbbackup.adapter.process.ProcessRunner;

class SqlServerPackContextTest {

    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withInitializer(applicationContext -> applicationContext.getBeanFactory()
                    .setConversionService(ApplicationConversionService.getSharedInstance()))
            .withUserConfiguration(PackConfiguration.class)
            .withBean(ProcessRunner.class);

    @TempDir
    Path temporaryDirectory;

    @Test
    void disabledPackPublishesNoSqlServerCapability() {
        context.withPropertyValues("dbbackup.sqlserver.enabled=false")
                .run(result -> {
                    assertThat(result).doesNotHaveBean(SqlServerCliConnectionTestAdapter.class);
                    assertThat(result).doesNotHaveBean(SqlServerBacpacBackupAdapter.class);
                    assertThat(result).doesNotHaveBean(SqlServerBacpacRestoreAdapter.class);
                    assertThat(result).doesNotHaveBean(SqlServerTemporaryFiles.class);
                });
    }

    @Test
    void enabledPackPublishesTheCompleteAdapterSet() throws Exception {
        Path binary = executable("client");

        enabled(binary, binary).run(result -> {
            assertThat(result).hasSingleBean(SqlServerCliConnectionTestAdapter.class);
            assertThat(result).hasSingleBean(SqlServerBacpacBackupAdapter.class);
            assertThat(result).hasSingleBean(SqlServerBacpacRestoreAdapter.class);
            assertThat(result).hasSingleBean(SqlServerTemporaryFiles.class);
        });
    }

    @Test
    void enabledPackFailsStartupWhenEitherBinaryIsMissing() throws Exception {
        Path sqlpackage = executable("sqlpackage");
        Path sqlcmd = executable("sqlcmd");
        Path missingSqlcmd = temporaryDirectory.resolve("missing-sqlcmd");
        Path missingSqlpackage = temporaryDirectory.resolve("missing-sqlpackage");

        enabled(sqlpackage, missingSqlcmd).run(result -> assertThat(result)
                .hasFailed()
                .getFailure()
                .hasRootCauseMessage(
                        "dbbackup.sqlserver.sqlcmd-path points at '%s', which is not an executable file"
                                .formatted(missingSqlcmd)));
        enabled(missingSqlpackage, sqlcmd).run(result -> assertThat(result)
                .hasFailed()
                .getFailure()
                .hasRootCauseMessage(
                        "dbbackup.sqlserver.sqlpackage-path points at '%s', which is not an executable file"
                                .formatted(missingSqlpackage)));
    }

    private ApplicationContextRunner enabled(Path sqlpackage, Path sqlcmd) {
        return context.withPropertyValues(
                "dbbackup.sqlserver.enabled=true",
                "dbbackup.sqlserver.sqlpackage-path=" + sqlpackage,
                "dbbackup.sqlserver.sqlcmd-path=" + sqlcmd,
                "dbbackup.sqlserver.connect-timeout=10s",
                "dbbackup.sqlserver.trust-server-certificate=false",
                "dbbackup.sqlserver.temp-directory=" + temporaryDirectory.resolve("scratch"),
                "dbbackup.backup.timeout=2m",
                "dbbackup.restore.timeout=3m");
    }

    private Path executable(String name) throws Exception {
        Path binary = temporaryDirectory.resolve(name);
        Files.writeString(binary, "#!/bin/sh\nexit 0\n");
        binary.toFile().setExecutable(true);
        return binary;
    }

    @Configuration(proxyBeanMethods = false)
    @Import({
            SqlServerCliConnectionTestAdapter.class,
            SqlServerBacpacBackupAdapter.class,
            SqlServerBacpacRestoreAdapter.class,
            SqlServerTemporaryFiles.class
    })
    static class PackConfiguration {
    }
}
