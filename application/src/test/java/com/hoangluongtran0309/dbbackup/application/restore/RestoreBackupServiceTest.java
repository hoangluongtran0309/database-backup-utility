package com.hoangluongtran0309.dbbackup.application.restore;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

import com.hoangluongtran0309.dbbackup.core.exception.RestoreFailedException;
import com.hoangluongtran0309.dbbackup.core.model.BackupExecution;
import com.hoangluongtran0309.dbbackup.core.model.DatabaseTarget;
import com.hoangluongtran0309.dbbackup.core.model.ExecutionStatus;
import com.hoangluongtran0309.dbbackup.core.model.MysqlConnection;
import com.hoangluongtran0309.dbbackup.core.model.RestoreExecution;
import com.hoangluongtran0309.dbbackup.core.port.BackupExecutionRepository;
import com.hoangluongtran0309.dbbackup.core.port.DatabaseTargetRepository;
import com.hoangluongtran0309.dbbackup.core.port.EncryptionPort;
import com.hoangluongtran0309.dbbackup.core.port.MysqlLogicalRestorePort;
import com.hoangluongtran0309.dbbackup.core.port.RestoreExecutionRepository;

@ExtendWith(MockitoExtension.class)
class RestoreBackupServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-09T12:00:00Z");
    private static final UUID TARGET_ID = UUID.randomUUID();
    private static final UUID BACKUP_ID = UUID.randomUUID();
    private static final String ARTIFACT = "/backups/shop_20260909_100000.sql.gz";

    @Mock private BackupExecutionRepository backups;
    @Mock private RestoreExecutionRepository restores;
    @Mock private DatabaseTargetRepository targets;
    @Mock private MysqlLogicalRestorePort restoreEngine;
    @Mock private EncryptionPort encryption;

    @Captor private ArgumentCaptor<RestoreExecution> saved;

    private final List<Runnable> queue = new ArrayList<>();

    private RestoreBackupService service;

    @BeforeEach
    void setUp() {
        service = newService(queue::add);
    }

    private RestoreBackupService newService(Executor executor) {
        return new RestoreBackupService(backups, restores, targets, restoreEngine, encryption,
                executor, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    // --- accepting ----------------------------------------------------------

    @Test
    void persistsTheRunningRowBeforeSubmittingTheWork() {
        givenSucceededBackup();
        givenTarget();
        givenSaveEchoes();
        Executor executor = Mockito.mock(Executor.class);

        newService(executor).start(BACKUP_ID);

        InOrder order = inOrder(restores, executor);
        order.verify(restores).save(any());
        order.verify(executor).execute(any());
    }

    @Test
    void returnsTheIdOfThePersistedRestore() {
        givenSucceededBackup();
        givenTarget();
        givenSaveEchoes();

        UUID id = service.start(BACKUP_ID);

        verify(restores).save(saved.capture());
        assertThat(id).isEqualTo(saved.getValue().getId());
        assertThat(saved.getValue().getStatus()).isEqualTo(ExecutionStatus.RUNNING);
        assertThat(saved.getValue().getBackupExecutionId()).isEqualTo(BACKUP_ID);
        assertThat(saved.getValue().getStartedAt()).isEqualTo(NOW);
    }

    /** There is no artifact behind a failed or running backup to restore from. */
    @Test
    void refusesToRestoreABackupThatDidNotSucceed() {
        when(backups.findById(BACKUP_ID)).thenReturn(Optional.of(
                BackupExecution.started(BACKUP_ID, TARGET_ID, NOW.minusSeconds(600))
                        .failed("mysqldump exited with 2", NOW.minusSeconds(590))));

        assertThatThrownBy(() -> service.start(BACKUP_ID))
                .isInstanceOf(RestoreFailedException.class)
                .hasMessageContaining("FAILED")
                .hasMessageContaining("nothing to restore");

        verifyNoInteractions(restores, restoreEngine);
    }

    @Test
    void refusesToRestoreABackupThatIsStillRunning() {
        when(backups.findById(BACKUP_ID)).thenReturn(Optional.of(
                BackupExecution.started(BACKUP_ID, TARGET_ID, NOW)));

        assertThatThrownBy(() -> service.start(BACKUP_ID))
                .isInstanceOf(RestoreFailedException.class)
                .hasMessageContaining("RUNNING");
    }

    @Test
    void failsForAnUnknownBackup() {
        when(backups.findById(BACKUP_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.start(BACKUP_ID))
                .isInstanceOf(NoSuchElementException.class);
        verifyNoInteractions(restores, restoreEngine);
    }

    @Test
    void failsWhenTheTargetTheBackupCameFromIsGone() {
        givenSucceededBackup();
        when(targets.findById(TARGET_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.start(BACKUP_ID))
                .isInstanceOf(NoSuchElementException.class)
                .hasMessageContaining("no longer exists");
        verifyNoInteractions(restores);
    }

    @Test
    void recordsAFailureWhenThePoolRefusesTheWork() {
        givenSucceededBackup();
        givenTarget();
        givenSaveEchoes();

        newService(runnable -> {
            throw new RejectedExecutionException("queue full");
        }).start(BACKUP_ID);

        verify(restores, Mockito.times(2)).save(saved.capture());
        assertThat(saved.getAllValues().get(1).getStatus()).isEqualTo(ExecutionStatus.FAILED);
        assertThat(saved.getAllValues().get(1).getErrorMessage()).contains("Too many jobs");
    }

    @Test
    void doesNotDecryptWhileMerelyAcceptingTheRestore() {
        givenSucceededBackup();
        givenTarget();
        givenSaveEchoes();

        service.start(BACKUP_ID);

        verifyNoInteractions(encryption);
    }

    // --- running ------------------------------------------------------------

    @Test
    void handsTheEngineTheDecryptedPasswordAndTheArtifact() {
        givenSucceededBackup();
        givenTarget();
        givenSaveEchoes();
        when(encryption.decrypt("sealed")).thenReturn("s3cr3t");

        runQueuedWork(service.start(BACKUP_ID));

        ArgumentCaptor<MysqlConnection> connection = ArgumentCaptor.forClass(MysqlConnection.class);
        verify(restoreEngine).restore(connection.capture(), eq(Path.of(ARTIFACT)));
        assertThat(connection.getValue().password()).isEqualTo("s3cr3t");
        assertThat(connection.getValue().database()).isEqualTo("shop");
        assertThat(lastSaved().getStatus()).isEqualTo(ExecutionStatus.SUCCEEDED);
        assertThat(lastSaved().getFinishedAt()).isEqualTo(NOW);
    }

    @Test
    void recordsTheEnginesOwnMessageOnFailure() {
        givenSucceededBackup();
        givenTarget();
        givenSaveEchoes();
        when(encryption.decrypt(any())).thenReturn("s3cr3t");
        Mockito.doThrow(new RestoreFailedException("mysql exited with 1: Access denied"))
                .when(restoreEngine).restore(any(), any());

        runQueuedWork(service.start(BACKUP_ID));

        assertThat(lastSaved().getStatus()).isEqualTo(ExecutionStatus.FAILED);
        assertThat(lastSaved().getErrorMessage()).isEqualTo("mysql exited with 1: Access denied");
    }

    @Test
    void recordsAFailureWhenSomethingUnexpectedBreaks() {
        givenSucceededBackup();
        givenTarget();
        givenSaveEchoes();
        when(encryption.decrypt(any())).thenThrow(new IllegalStateException("key rotated"));

        runQueuedWork(service.start(BACKUP_ID));

        assertThat(lastSaved().getStatus()).isEqualTo(ExecutionStatus.FAILED);
        assertThat(lastSaved().getErrorMessage()).contains("key rotated");
    }

    // --- repairing after a restart -----------------------------------------

    @Test
    void marksRestoresLeftRunningByAPreviousProcessAsFailed() {
        RestoreExecution stranded =
                RestoreExecution.started(UUID.randomUUID(), BACKUP_ID, NOW.minusSeconds(900));
        when(restores.findRunning()).thenReturn(List.of(stranded));
        givenSaveEchoes();

        assertThat(service.failInterruptedRestores()).isEqualTo(1);
        assertThat(lastSaved().getErrorMessage()).contains("the application stopped");
    }

    @Test
    void repairingNothingIsHarmless() {
        when(restores.findRunning()).thenReturn(List.of());

        assertThat(service.failInterruptedRestores()).isZero();
        verify(restores, never()).save(any());
    }

    // --- helpers ------------------------------------------------------------

    private void givenSucceededBackup() {
        when(backups.findById(BACKUP_ID)).thenReturn(Optional.of(
                BackupExecution.started(BACKUP_ID, TARGET_ID, NOW.minusSeconds(600))
                        .succeeded(ARTIFACT, 1024L, NOW.minusSeconds(500))));
    }

    private void givenTarget() {
        when(targets.findById(TARGET_ID)).thenReturn(Optional.of(DatabaseTarget.builder()
                .id(TARGET_ID).name("production").host("db.internal").port(3307)
                .databaseName("shop").username("backup").passwordCiphertext("sealed")
                .createdAt(NOW).build()));
    }

    private void givenSaveEchoes() {
        when(restores.save(any())).thenAnswer(call -> call.getArgument(0));
    }

    private void runQueuedWork(UUID restoreId) {
        when(restores.findById(restoreId)).thenReturn(Optional.of(
                RestoreExecution.started(restoreId, BACKUP_ID, NOW)));
        queue.forEach(Runnable::run);
    }

    private RestoreExecution lastSaved() {
        verify(restores, Mockito.atLeastOnce()).save(saved.capture());
        return saved.getValue();
    }
}
