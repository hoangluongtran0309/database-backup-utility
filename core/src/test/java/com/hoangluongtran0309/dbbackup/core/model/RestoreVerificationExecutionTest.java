package com.hoangluongtran0309.dbbackup.core.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class RestoreVerificationExecutionTest {
    private static final Instant NOW = Instant.parse("2026-09-24T08:00:00Z");

    @Test
    void successCarriesIndependentRestoreEvidence() {
        RestoreVerificationExecution running = RestoreVerificationExecution.started(
                UUID.randomUUID(), UUID.randomUUID(), NOW);

        RestoreVerificationExecution done = running.succeeded(
                new RestoreVerificationResult(3, "three tables passed"), NOW.plusSeconds(4));

        assertThat(done.getStatus()).isEqualTo(RestoreVerificationStatus.SUCCEEDED);
        assertThat(done.getCheckedObjects()).isEqualTo(3);
        assertThat(done.getResultSummary()).isEqualTo("three tables passed");
        assertThat(done.getErrorMessage()).isNull();
    }

    @Test
    void terminalAttemptCannotBeOverwritten() {
        RestoreVerificationExecution failed = RestoreVerificationExecution.started(
                UUID.randomUUID(), UUID.randomUUID(), NOW).failed("bad archive", NOW.plusSeconds(1));

        assertThatThrownBy(() -> failed.succeeded(
                new RestoreVerificationResult(0, "restored"), NOW.plusSeconds(2)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void failureAlwaysHasAUsefulBoundedMessage() {
        RestoreVerificationExecution failed = RestoreVerificationExecution.started(
                UUID.randomUUID(), UUID.randomUUID(), NOW).failed("x".repeat(3000), NOW.plusSeconds(1));

        assertThat(failed.getErrorMessage()).hasSize(RestoreVerificationExecution.MAX_MESSAGE_LENGTH);
    }

    @Test
    void optionalEnginesCannotEnableAutomaticVerification() {
        assertThatThrownBy(() -> DatabaseTarget.builder()
                .id(UUID.randomUUID()).name("oracle").engine(DatabaseEngine.ORACLE)
                .host("db").port(1521).databaseName("FREEPDB1").username("APP")
                .dataPumpDirectory("PUMP").passwordCiphertext("sealed").createdAt(NOW)
                .verifyAfterBackup(true).build())
                .hasMessageContaining("not supported");
    }
}
