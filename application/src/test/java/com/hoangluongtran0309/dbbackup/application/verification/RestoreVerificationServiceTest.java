package com.hoangluongtran0309.dbbackup.application.verification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

import com.hoangluongtran0309.dbbackup.application.backup.BackupActivityGuard;
import com.hoangluongtran0309.dbbackup.application.notification.NotificationDispatcher;
import com.hoangluongtran0309.dbbackup.application.storage.ArtifactStorageService;
import com.hoangluongtran0309.dbbackup.core.model.BackupExecution;
import com.hoangluongtran0309.dbbackup.core.model.DatabaseEngine;
import com.hoangluongtran0309.dbbackup.core.model.DatabaseTarget;
import com.hoangluongtran0309.dbbackup.core.model.NotificationEventType;
import com.hoangluongtran0309.dbbackup.core.model.RestoreVerificationExecution;
import com.hoangluongtran0309.dbbackup.core.model.RestoreVerificationResult;
import com.hoangluongtran0309.dbbackup.core.model.RestoreVerificationStatus;
import com.hoangluongtran0309.dbbackup.core.port.BackupExecutionRepository;
import com.hoangluongtran0309.dbbackup.core.port.DatabaseTargetRepository;
import com.hoangluongtran0309.dbbackup.core.port.RestoreVerificationExecutionRepository;
import com.hoangluongtran0309.dbbackup.core.port.RestoreVerificationPort;
import com.hoangluongtran0309.dbbackup.core.port.StoragePort;

@ExtendWith(MockitoExtension.class)
class RestoreVerificationServiceTest {
    private static final Instant NOW = Instant.parse("2026-09-24T08:00:00Z");
    private static final UUID TARGET_ID = UUID.randomUUID();
    private static final UUID BACKUP_ID = UUID.randomUUID();
    private static final Path ARTIFACT = Path.of("/backups/shop.sql.gz");
    private static final String SHA = "9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08";

    @Mock private BackupExecutionRepository backups;
    @Mock private RestoreVerificationExecutionRepository executions;
    @Mock private DatabaseTargetRepository targets;
    @Mock private StoragePort storage;
    @Mock private RestoreVerificationPort adapter;
    @Mock private NotificationDispatcher notifications;

    private final List<Runnable> queue = new ArrayList<>();
    private final Map<UUID, RestoreVerificationExecution> saved = new HashMap<>();
    private RestoreVerificationService service;

    @BeforeEach
    void setUp() {
        when(adapter.engine()).thenReturn(DatabaseEngine.MYSQL);
        Mockito.lenient().when(adapter.isAvailable()).thenReturn(true);
        Mockito.lenient().when(executions.save(any())).thenAnswer(call -> {
            RestoreVerificationExecution value = call.getArgument(0);
            saved.put(value.getId(), value);
            return value;
        });
        Mockito.lenient().when(executions.findById(any()))
                .thenAnswer(call -> Optional.ofNullable(saved.get(call.getArgument(0))));
        service = service(queue::add, true);
    }

    @Test
    void manualAttemptPersistsBeforeItIsQueuedAndPublishesSuccessAfterHealthCheck() {
        givenContext(false);
        when(adapter.verify(any(), any(), eq(ARTIFACT)))
                .thenReturn(new RestoreVerificationResult(2, "two tables passed"));

        UUID id = service.start(BACKUP_ID);
        assertThat(saved.get(id).getStatus()).isEqualTo(RestoreVerificationStatus.RUNNING);
        queue.removeFirst().run();

        assertThat(saved.get(id).getStatus()).isEqualTo(RestoreVerificationStatus.SUCCEEDED);
        verify(notifications).publishVerification(any(), eq(saved.get(id)),
                eq(NotificationEventType.VERIFICATION_SUCCESS));
    }

    @Test
    void checksumMismatchFailsBeforeTheAdapterAndLeavesBackupUntouched() {
        givenContext(false);
        when(storage.sha256Of(ARTIFACT)).thenReturn("0".repeat(64));

        UUID id = service.start(BACKUP_ID);
        queue.removeFirst().run();

        assertThat(saved.get(id).getStatus()).isEqualTo(RestoreVerificationStatus.FAILED);
        assertThat(saved.get(id).getErrorMessage()).contains("checksum");
        verify(adapter, never()).verify(any(), any(), any());
        verify(backups, never()).save(any());
    }

    @Test
    void queueRejectionIsARecordedVerificationFailure() {
        givenContext(false);
        RestoreVerificationService rejecting = service(command -> { throw new RejectedExecutionException(); }, true);

        UUID id = rejecting.start(BACKUP_ID);

        assertThat(saved.get(id).getStatus()).isEqualTo(RestoreVerificationStatus.FAILED);
        verify(notifications).publishVerification(any(), eq(saved.get(id)),
                eq(NotificationEventType.VERIFICATION_FAILED));
    }

    @Test
    void manualStartRefusesDisabledOrUnavailableVerificationBeforeWritingHistory() {
        RestoreVerificationService disabled = service(queue::add, false);

        assertThatThrownBy(() -> disabled.start(BACKUP_ID)).hasMessageContaining("disabled");
        verify(executions, never()).existsRunningForBackup(any());
    }

    @Test
    void automaticVerificationRunsInlineOnlyForOptedInTargets() {
        givenContext(true);
        when(adapter.verify(any(), any(), eq(ARTIFACT)))
                .thenReturn(new RestoreVerificationResult(0, "empty database restored"));
        BackupExecution backup = successfulBackup();
        DatabaseTarget target = target(true);

        assertThat(service.verifyAfterBackup(target, backup)).isPresent();
        assertThat(saved.values()).singleElement().extracting(RestoreVerificationExecution::getStatus)
                .isEqualTo(RestoreVerificationStatus.SUCCEEDED);
        assertThat(queue).isEmpty();
    }

    @Test
    void missingArtifactCreatesAFailedAttemptWithoutOpeningATemporaryDatabase() {
        givenContext(false);
        when(storage.exists(ARTIFACT)).thenReturn(false);

        UUID id = service.start(BACKUP_ID);
        queue.removeFirst().run();

        assertThat(saved.get(id).getStatus()).isEqualTo(RestoreVerificationStatus.FAILED);
        assertThat(saved.get(id).getErrorMessage()).contains("no longer available");
        verify(adapter, never()).verify(any(), any(), any());
    }

    @Test
    void adapterOrCleanupFailureChangesOnlyTheVerificationAttempt() {
        givenContext(false);
        when(adapter.verify(any(), any(), eq(ARTIFACT)))
                .thenThrow(new IllegalStateException("temporary database cleanup failed"));

        UUID id = service.start(BACKUP_ID);
        queue.removeFirst().run();

        assertThat(saved.get(id).getStatus()).isEqualTo(RestoreVerificationStatus.FAILED);
        assertThat(saved.get(id).getErrorMessage()).contains("cleanup failed");
        verify(backups, never()).save(any());
    }

    @Test
    void refusesASecondRunningAttemptBeforeCreatingHistory() {
        givenContext(false);
        when(executions.existsRunningForBackup(BACKUP_ID)).thenReturn(true);

        assertThatThrownBy(() -> service.start(BACKUP_ID)).hasMessageContaining("already running");
    }

    @Test
    void startupRepairFailsRunningAttemptCleansItsDeterministicResourceAndNotifies() {
        givenContext(false);
        RestoreVerificationExecution running = RestoreVerificationExecution.started(
                UUID.randomUUID(), BACKUP_ID, NOW.minusSeconds(10));
        saved.put(running.getId(), running);
        when(executions.findRunning()).thenReturn(List.of(running));

        assertThat(service.failInterruptedVerifications()).isOne();

        assertThat(saved.get(running.getId()).getStatus()).isEqualTo(RestoreVerificationStatus.FAILED);
        verify(adapter).abortInterrupted(running.getId());
        verify(notifications).publishVerification(any(), eq(saved.get(running.getId())),
                eq(NotificationEventType.VERIFICATION_FAILED));
    }

    @Test
    void automaticVerificationDoesNothingWhenTheTargetDidNotOptIn() {
        BackupExecution backup = successfulBackup();

        assertThat(service.verifyAfterBackup(target(false), backup)).isEmpty();
        verify(executions, never()).existsRunningForBackup(any());
    }

    private RestoreVerificationService service(Executor executor, boolean enabled) {
        return new RestoreVerificationService(backups, executions, targets,
                ArtifactStorageService.localOnly(storage), executor,
                Clock.fixed(NOW, ZoneOffset.UTC), new BackupActivityGuard(), notifications,
                List.of(adapter), enabled);
    }

    private void givenContext(boolean optedIn) {
        when(backups.findById(BACKUP_ID)).thenReturn(Optional.of(successfulBackup()));
        when(targets.findById(TARGET_ID)).thenReturn(Optional.of(target(optedIn)));
        Mockito.lenient().when(storage.exists(ARTIFACT)).thenReturn(true);
        Mockito.lenient().when(storage.sha256Of(ARTIFACT)).thenReturn(SHA);
    }

    private static BackupExecution successfulBackup() {
        return BackupExecution.started(BACKUP_ID, TARGET_ID, NOW.minusSeconds(10))
                .succeeded(ARTIFACT.toString(), 100, SHA, NOW.minusSeconds(5));
    }

    private static DatabaseTarget target(boolean optedIn) {
        return DatabaseTarget.builder().id(TARGET_ID).name("production").engine(DatabaseEngine.MYSQL)
                .host("db").port(3306).databaseName("shop").username("backup")
                .passwordCiphertext("sealed").createdAt(NOW.minusSeconds(100))
                .verifyAfterBackup(optedIn).build();
    }
}
