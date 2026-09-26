package com.hoangluongtran0309.dbbackup.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;

import org.junit.jupiter.api.Test;

class CliArgumentsTest {
    @Test
    void parsesGlobalAndResourceOptionsInAnyPosition() {
        CliArguments args = CliArguments.parse(new String[] {
                "target", "add", "--server", "https://backup.example/base", "--output=json",
                "--target-id", "123", "--verify-after-backup", "true", "--no-wait"
        }, Map.of("DBBACKUP_API_USERNAME", "operator"));

        assertThat(args.group()).isEqualTo("target");
        assertThat(args.action()).isEqualTo("add");
        assertThat(args.server()).isEqualTo("https://backup.example/base");
        assertThat(args.username()).isEqualTo("operator");
        assertThat(args.output()).isEqualTo("json");
        assertThat(args.optional("targetId")).isEqualTo("123");
        assertThat(args.optional("verifyAfterBackup")).isEqualTo("true");
        assertThat(args.waitForCompletion()).isFalse();
    }

    @Test
    void preservesRepeatedSubscriptionOptions() {
        CliArguments args = CliArguments.parse(new String[] {
                "subscription", "set", "--subscription", "one:BACKUP_FAILED",
                "--subscription=two:RESTORE_FAILED,VERIFICATION_FAILED"
        }, Map.of());

        assertThat(args.values("subscription")).containsExactly(
                "one:BACKUP_FAILED", "two:RESTORE_FAILED,VERIFICATION_FAILED");
    }

    @Test
    void rejectsAmbiguousPasswordSources() {
        assertThatThrownBy(() -> CliArguments.parse(new String[] {
                "target", "list", "--password-file", "secret", "--password-stdin"
        }, Map.of())).isInstanceOf(CliException.class)
                .hasMessageContaining("only one");
    }

    @Test
    void resolvesResourceSecretsFromNamedEnvironmentVariables() {
        CliArguments args = CliArguments.parse(new String[] {
                "target", "add", "--name", "production", "--secret", "password=TARGET_PASSWORD"
        }, Map.of());

        assertThat(DbBackupCli.body(args, Map.of("TARGET_PASSWORD", "not-on-argv")))
                .containsEntry("name", "production")
                .containsEntry("password", "not-on-argv");
    }

    @Test
    void rejectsAResourceSecretPlacedDirectlyOnArgv() {
        CliArguments args = CliArguments.parse(new String[] {
                "target", "add", "--password", "visible-to-ps"
        }, Map.of());

        assertThatThrownBy(() -> DbBackupCli.body(args, Map.of()))
                .isInstanceOf(CliException.class)
                .hasMessageContaining("process list");
    }
}
