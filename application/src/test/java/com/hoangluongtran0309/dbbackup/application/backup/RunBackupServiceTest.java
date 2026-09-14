package com.hoangluongtran0309.dbbackup.application.backup;

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
import org.mockito.junit.jupiter.MockitoExtension;

import com.hoangluongtran0309.dbbackup.core.exception.BackupFailedException;
import com.hoangluongtran0309.dbbackup.core.model.BackupExecution;
import com.hoangluongtran0309.dbbackup.core.model.ExecutionStatus;
import com.hoangluongtran0309.dbbackup.core.model.DatabaseTarget;
import com.hoangluongtran0309.dbbackup.core.model.MysqlConnection;
import com.hoangluongtran0309.dbbackup.core.port.BackupExecutionRepository;
import com.hoangluongtran0309.dbbackup.core.port.DatabaseTargetRepository;
import com.hoangluongtran0309.dbbackup.core.port.EncryptionPort;
import com.hoangluongtran0309.dbbackup.core.port.MysqlLogicalBackupPort;
import com.hoangluongtran0309.dbbackup.core.port.StoragePort;

@ExtendWith(MockitoExtension.class)
class RunBackupServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-09T10:15:30Z");
    private static final UUID TARGET_ID = UUID.randomUUID();
    private static final Path ARTIFACT = Path.of("/backups/shop_20260909_101530.sql.gz");

    @Mock private DatabaseTargetRepository targets;
    @Mock private BackupExecutionRepository executions;
    @Mock private MysqlLogicalBackupPort backupEngine;
    @Mock private StoragePort storage;
    @Mock private EncryptionPort encryption;

    @Captor private ArgumentCaptor<BackupExecution> saved;

    /** Holds submitted work so a test can decide when, or whether, it runs. */
    private final List<Runnable> queue = new ArrayList<>();
    private final Executor capturingExecutor = queue::add;

    private RunBackupService service;

    @BeforeEach
    void setUp() {
        service = newService(capturingExecutor);
    }

    private RunBackupService newService(Executor executor) {
        return new RunBackupService(targets, executions, backupEngine, storage, encryption,
                executor, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    // --- accepting a backup -------------------------------------------------

    /**
     * The property ADR-004 exists for. If the work were submitted first, the
     * background thread could look for a row that has not been written.
     */
    @Test
    void persistsTheRunningRowBeforeSubmittingTheWork() {
        givenTarget();
        givenSaveEchoes();
        Executor executor = org.mockito.Mockito.mock(Executor.class);
        RunBackupService withMockExecutor = newService(executor);

        withMockExecutor.start(TARGET_ID);

        InOrder order = inOrder(executions, executor);
        order.verify(executions).save(any());
        order.verify(executor).execute(any());
    }

    @Test
    void returnsTheIdOfThePersistedExecution() {
        givenTarget();
        givenSaveEchoes();

        UUID executionId = service.start(TARGET_ID);

        verify(executions).save(saved.capture());
        assertThat(executionId).isEqualTo(saved.getValue().getId());
        assertThat(saved.getValue().getStatus()).isEqualTo(ExecutionStatus.RUNNING);
        assertThat(saved.getValue().getStartedAt()).isEqualTo(NOW);
        assertThat(saved.getValue().getTargetId()).isEqualTo(TARGET_ID);
    }

    /** The password is not carried through the queue; it is unwrapped when used. */
    @Test
    void doesNotDecryptWhileMerelyAcceptingTheBackup() {
        givenTarget();
        givenSaveEchoes();

        service.start(TARGET_ID);

        verifyNoInteractions(encryption);
        assertThat(queue).hasSize(1);
    }

    @Test
    void failsForAnUnknownTargetWithoutPersistingOrQueueingAnything() {
        when(targets.findById(TARGET_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.start(TARGET_ID))
                .isInstanceOf(NoSuchElementException.class)
                .hasMessageContaining(TARGET_ID.toString());

        verifyNoInteractions(executions, backupEngine, storage);
        assertThat(queue).isEmpty();
    }

    /**
     * A full queue must produce a visible failure, not a row that stays RUNNING
     * because nothing ever picked it up.
     */
    @Test
    void recordsAFailureWhenThePoolRefusesTheWork() {
        givenTarget();
        givenSaveEchoes();
        RunBackupService rejecting = newService(runnable -> {
            throw new RejectedExecutionException("queue full");
        });

        rejecting.start(TARGET_ID);

        verify(executions, org.mockito.Mockito.times(2)).save(saved.capture());
        BackupExecution recorded = saved.getAllValues().get(1);
        assertThat(recorded.getStatus()).isEqualTo(ExecutionStatus.FAILED);
        assertThat(recorded.getErrorMessage()).contains("Too many jobs");
    }

    // --- running it ---------------------------------------------------------

    @Test
    void recordsSuccessWithTheArtifactPathAndSize() {
        givenTarget();
        givenSaveEchoes();
        when(encryption.decrypt("sealed")).thenReturn("s3cr3t");
        when(storage.locationFor("shop_20260909_101530.sql.gz")).thenReturn(ARTIFACT);
        when(backupEngine.dumpTo(any(), eq(ARTIFACT))).thenReturn(8192L);

        runQueuedWork(service.start(TARGET_ID));

        BackupExecution recorded = lastSaved();
        assertThat(recorded.getStatus()).isEqualTo(ExecutionStatus.SUCCEEDED);
        assertThat(recorded.getArtifactPath()).isEqualTo(ARTIFACT.toString());
        assertThat(recorded.getSizeBytes()).isEqualTo(8192L);
        assertThat(recorded.getFinishedAt()).isEqualTo(NOW);
    }

    @Test
    void handsTheEngineTheDecryptedPassword() {
        givenTarget();
        givenSaveEchoes();
        when(encryption.decrypt("sealed")).thenReturn("s3cr3t");
        when(storage.locationFor(any())).thenReturn(ARTIFACT);
        when(backupEngine.dumpTo(any(), any())).thenReturn(1L);

        runQueuedWork(service.start(TARGET_ID));

        ArgumentCaptor<MysqlConnection> connection = ArgumentCaptor.forClass(MysqlConnection.class);
        verify(backupEngine).dumpTo(connection.capture(), eq(ARTIFACT));
        assertThat(connection.getValue().password()).isEqualTo("s3cr3t");
        assertThat(connection.getValue().database()).isEqualTo("shop");
    }

    @Test
    void recordsTheEnginesOwnMessageOnFailure() {
        givenTarget();
        givenSaveEchoes();
        when(encryption.decrypt(any())).thenReturn("s3cr3t");
        when(storage.locationFor(any())).thenReturn(ARTIFACT);
        when(backupEngine.dumpTo(any(), any()))
                .thenThrow(new BackupFailedException("mysqldump exited with 2: Access denied"));

        runQueuedWork(service.start(TARGET_ID));

        BackupExecution recorded = lastSaved();
        assertThat(recorded.getStatus()).isEqualTo(ExecutionStatus.FAILED);
        assertThat(recorded.getErrorMessage()).isEqualTo("mysqldump exited with 2: Access denied");
        // The engine already removed its own partial file.
        verify(storage, never()).delete(any());
    }

    /**
     * Anything unexpected still has to land on the row, or the console shows
     * RUNNING for a job that has already died.
     */
    @Test
    void recordsAFailureAndClearsTheFileWhenSomethingUnexpectedBreaks() {
        givenTarget();
        givenSaveEchoes();
        when(encryption.decrypt(any())).thenThrow(new IllegalStateException("key rotated"));
        when(storage.locationFor(any())).thenReturn(ARTIFACT);

        runQueuedWork(service.start(TARGET_ID));

        assertThat(lastSaved().getStatus()).isEqualTo(ExecutionStatus.FAILED);
        assertThat(lastSaved().getErrorMessage()).contains("key rotated");
        verify(storage).delete(ARTIFACT);
    }

    // --- repairing after a restart -----------------------------------------

    @Test
    void marksBackupsLeftRunningByAPreviousProcessAsFailed() {
        BackupExecution stranded = BackupExecution.started(UUID.randomUUID(), TARGET_ID, NOW.minusSeconds(600));
        when(executions.findRunning()).thenReturn(List.of(stranded));
        givenSaveEchoes();

        assertThat(service.failInterruptedBackups()).isEqualTo(1);

        BackupExecution recorded = lastSaved();
        assertThat(recorded.getStatus()).isEqualTo(ExecutionStatus.FAILED);
        assertThat(recorded.getErrorMessage()).contains("the application stopped");
    }

    @Test
    void repairingNothingIsHarmless() {
        when(executions.findRunning()).thenReturn(List.of());

        assertThat(service.failInterruptedBackups()).isZero();
        verify(executions, never()).save(any());
    }

    // --- artifact naming ----------------------------------------------------

    @Test
    void namesTheArtifactAfterTheSchemaAndTheUtcStartTime() {
        assertThat(RunBackupService.artifactFileName(target("shop"), NOW))
                .isEqualTo("shop_20260909_101530.sql.gz");
    }

    /** The schema name is typed by a user and is about to become a file name. */
    @Test
    void sanitisesASchemaNameThatWouldEscapeTheStorageDirectory() {
        // Dots survive, separators do not, so the result is still a bare name.
        assertThat(RunBackupService.artifactFileName(target("../../etc"), NOW))
                .isEqualTo(".._.._etc_20260909_101530.sql.gz")
                .doesNotContain("/");
    }

    @Test
    void sanitisesSpacesAndQuotesInASchemaName() {
        assertThat(RunBackupService.artifactFileName(target("my db'; drop"), NOW))
                .isEqualTo("my_db___drop_20260909_101530.sql.gz");
    }

    // --- helpers ------------------------------------------------------------

    private void givenTarget() {
        when(targets.findById(TARGET_ID)).thenReturn(Optional.of(target("shop")));
    }

    private void givenSaveEchoes() {
        when(executions.save(any())).thenAnswer(call -> call.getArgument(0));
    }

    private void runQueuedWork(UUID executionId) {
        when(executions.findById(executionId)).thenReturn(Optional.of(
                BackupExecution.started(executionId, TARGET_ID, NOW)));
        queue.forEach(Runnable::run);
    }

    private BackupExecution lastSaved() {
        verify(executions, org.mockito.Mockito.atLeastOnce()).save(saved.capture());
        return saved.getValue();
    }

    private static DatabaseTarget target(String schema) {
        return DatabaseTarget.builder()
                .id(TARGET_ID)
                .name("production")
                .host("db.internal")
                .port(3307)
                .databaseName(schema)
                .username("backup")
                .passwordCiphertext("sealed")
                .createdAt(NOW)
                .build();
    }
}
