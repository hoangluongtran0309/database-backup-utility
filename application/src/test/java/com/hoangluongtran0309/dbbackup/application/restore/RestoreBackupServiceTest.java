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

import com.hoangluongtran0309.dbbackup.application.EngineAdapterRegistry;
import com.hoangluongtran0309.dbbackup.core.exception.RestoreFailedException;
import com.hoangluongtran0309.dbbackup.core.model.BackupExecution;
import com.hoangluongtran0309.dbbackup.core.model.DatabaseEngine;
import com.hoangluongtran0309.dbbackup.core.model.DatabaseTarget;
import com.hoangluongtran0309.dbbackup.core.model.ExecutionStatus;
import com.hoangluongtran0309.dbbackup.core.model.DatabaseConnection;
import com.hoangluongtran0309.dbbackup.core.model.RestoreExecution;
import com.hoangluongtran0309.dbbackup.core.port.BackupExecutionRepository;
import com.hoangluongtran0309.dbbackup.core.port.DatabaseTargetRepository;
import com.hoangluongtran0309.dbbackup.core.port.EncryptionPort;
import com.hoangluongtran0309.dbbackup.core.port.LogicalRestorePort;
import com.hoangluongtran0309.dbbackup.core.port.RestoreExecutionRepository;
import com.hoangluongtran0309.dbbackup.core.port.StoragePort;

@ExtendWith(MockitoExtension.class)
class RestoreBackupServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-09T12:00:00Z");
    private static final UUID TARGET_ID = UUID.randomUUID();
    private static final UUID BACKUP_ID = UUID.randomUUID();
    private static final String ARTIFACT = "/backups/shop_20260909_100000.sql.gz";
    private static final String SHA256 = "9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08";

    @Mock private BackupExecutionRepository backups;
    @Mock private RestoreExecutionRepository restores;
    @Mock private DatabaseTargetRepository targets;
    @Mock private EngineAdapterRegistry adapters;
    @Mock private LogicalRestorePort restoreEngine;
    @Mock private StoragePort storage;
    @Mock private EncryptionPort encryption;

    @Captor private ArgumentCaptor<RestoreExecution> saved;

    private final List<Runnable> queue = new ArrayList<>();

    private RestoreBackupService service;

    @BeforeEach
    void setUp() {
        Mockito.lenient().when(adapters.restoreFor(DatabaseEngine.MYSQL)).thenReturn(restoreEngine);
        service = newService(queue::add);
    }

    private RestoreBackupService newService(Executor executor) {
        return new RestoreBackupService(backups, restores, targets, adapters, storage, encryption,
                executor, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    // --- accepting ----------------------------------------------------------

    @Test
    void persistsTheRunningRowBeforeSubmittingTheWork() {
        givenSucceededBackup();
        givenTarget();
        givenSaveEchoes();
        Executor executor = Mockito.mock(Executor.class);

        newService(executor).start(BACKUP_ID, TARGET_ID);

        InOrder order = inOrder(restores, executor);
        order.verify(restores).save(any());
        order.verify(executor).execute(any());
    }

    @Test
    void returnsTheIdOfThePersistedRestore() {
        givenSucceededBackup();
        givenTarget();
        givenSaveEchoes();

        UUID id = service.start(BACKUP_ID, TARGET_ID);

        verify(restores).save(saved.capture());
        assertThat(id).isEqualTo(saved.getValue().getId());
        assertThat(saved.getValue().getStatus()).isEqualTo(ExecutionStatus.RUNNING);
        assertThat(saved.getValue().getBackupExecutionId()).isEqualTo(BACKUP_ID);
        assertThat(saved.getValue().getTargetId()).isEqualTo(TARGET_ID);
        assertThat(saved.getValue().getStartedAt()).isEqualTo(NOW);
    }

    /** There is no artifact behind a failed or running backup to restore from. */
    @Test
    void refusesToRestoreABackupThatDidNotSucceed() {
        when(backups.findById(BACKUP_ID)).thenReturn(Optional.of(
                BackupExecution.started(BACKUP_ID, TARGET_ID, NOW.minusSeconds(600))
                        .failed("mysqldump exited with 2", NOW.minusSeconds(590))));

        assertThatThrownBy(() -> service.start(BACKUP_ID, TARGET_ID))
                .isInstanceOf(RestoreFailedException.class)
                .hasMessageContaining("FAILED")
                .hasMessageContaining("nothing to restore");

        verifyNoInteractions(restores, restoreEngine);
    }

    @Test
    void refusesToRestoreABackupThatIsStillRunning() {
        when(backups.findById(BACKUP_ID)).thenReturn(Optional.of(
                BackupExecution.started(BACKUP_ID, TARGET_ID, NOW)));

        assertThatThrownBy(() -> service.start(BACKUP_ID, TARGET_ID))
                .isInstanceOf(RestoreFailedException.class)
                .hasMessageContaining("RUNNING");
    }

    @Test
    void failsForAnUnknownBackup() {
        when(backups.findById(BACKUP_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.start(BACKUP_ID, TARGET_ID))
                .isInstanceOf(NoSuchElementException.class);
        verifyNoInteractions(restores, restoreEngine);
    }

    @Test
    void failsWhenTheTargetToRestoreIntoIsGone() {
        givenSucceededBackup();
        when(targets.findById(TARGET_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.start(BACKUP_ID, TARGET_ID))
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
        }).start(BACKUP_ID, TARGET_ID);

        verify(restores, Mockito.times(2)).save(saved.capture());
        assertThat(saved.getAllValues().get(1).getStatus()).isEqualTo(ExecutionStatus.FAILED);
        assertThat(saved.getAllValues().get(1).getErrorMessage()).contains("Too many jobs");
    }

    @Test
    void doesNotDecryptWhileMerelyAcceptingTheRestore() {
        givenSucceededBackup();
        givenTarget();
        givenSaveEchoes();

        service.start(BACKUP_ID, TARGET_ID);

        verifyNoInteractions(encryption);
    }

    // --- running ------------------------------------------------------------

    @Test
    void handsTheEngineTheDecryptedPasswordAndTheArtifact() {
        givenSucceededBackup();
        givenTarget();
        givenSaveEchoes();
        givenArtifactIntact();
        when(encryption.decrypt("sealed")).thenReturn("s3cr3t");

        runQueuedWork(service.start(BACKUP_ID, TARGET_ID));

        ArgumentCaptor<DatabaseConnection> connection = ArgumentCaptor.forClass(DatabaseConnection.class);
        verify(restoreEngine).restore(connection.capture(), eq("shop"), eq(Path.of(ARTIFACT)));
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
        givenArtifactIntact();
        when(encryption.decrypt(any())).thenReturn("s3cr3t");
        Mockito.doThrow(new RestoreFailedException("mysql exited with 1: Access denied"))
                .when(restoreEngine).restore(any(), any(), any());

        runQueuedWork(service.start(BACKUP_ID, TARGET_ID));

        assertThat(lastSaved().getStatus()).isEqualTo(ExecutionStatus.FAILED);
        assertThat(lastSaved().getErrorMessage()).isEqualTo("mysql exited with 1: Access denied");
    }

    @Test
    void recordsAFailureWhenSomethingUnexpectedBreaks() {
        givenSucceededBackup();
        givenTarget();
        givenSaveEchoes();
        givenArtifactIntact();
        when(encryption.decrypt(any())).thenThrow(new IllegalStateException("key rotated"));

        runQueuedWork(service.start(BACKUP_ID, TARGET_ID));

        assertThat(lastSaved().getStatus()).isEqualTo(ExecutionStatus.FAILED);
        assertThat(lastSaved().getErrorMessage()).contains("key rotated");
    }

    /** ADR-014: the data goes where the operator chose, with that target's credentials. */
    @Test
    void restoresIntoAnotherTargetWithThatTargetsConnection() {
        UUID otherId = UUID.randomUUID();
        givenSucceededBackup();
        givenTarget();
        when(targets.findById(otherId)).thenReturn(Optional.of(DatabaseTarget.builder().engine(com.hoangluongtran0309.dbbackup.core.model.DatabaseEngine.MYSQL)
                .id(otherId).name("drill").host("scratch.internal").port(3306)
                .databaseName("shop_restore_test").username("drill").passwordCiphertext("drill-sealed")
                .createdAt(NOW).build()));
        givenSaveEchoes();
        givenArtifactIntact();
        when(encryption.decrypt("drill-sealed")).thenReturn("dr1ll");

        UUID restoreId = service.start(BACKUP_ID, otherId);
        when(restores.findById(restoreId)).thenReturn(Optional.of(
                RestoreExecution.started(restoreId, BACKUP_ID, otherId, NOW)));
        queue.forEach(Runnable::run);

        ArgumentCaptor<DatabaseConnection> connection = ArgumentCaptor.forClass(DatabaseConnection.class);
        verify(restoreEngine).restore(connection.capture(), eq("shop"), eq(Path.of(ARTIFACT)));
        assertThat(connection.getValue().host()).isEqualTo("scratch.internal");
        assertThat(connection.getValue().database()).isEqualTo("shop_restore_test");
        assertThat(connection.getValue().password()).isEqualTo("dr1ll");
        assertThat(lastSaved().getTargetId()).isEqualTo(otherId);
        verify(targets).findById(TARGET_ID);
    }

    @Test
    void rejectsACrossEngineRestoreBeforePersistingAJob() {
        UUID postgresId = UUID.randomUUID();
        givenSucceededBackup();
        givenTarget();
        when(targets.findById(postgresId)).thenReturn(Optional.of(DatabaseTarget.builder()
                .engine(DatabaseEngine.POSTGRESQL)
                .id(postgresId).name("postgres-drill").host("postgres.internal").port(5432)
                .databaseName("shop_restore_test").username("drill").passwordCiphertext("sealed")
                .createdAt(NOW).build()));

        assertThatThrownBy(() -> service.start(BACKUP_ID, postgresId))
                .isInstanceOf(RestoreFailedException.class)
                .hasMessageContaining("MySQL backup")
                .hasMessageContaining("MySQL target");
        verify(restores, never()).save(any());
        assertThat(queue).isEmpty();
    }

    // --- checking the artifact first ------------------------------------------

    /** ADR-013: a changed file is not applied, and the target is never contacted. */
    @Test
    void anArtifactThatNoLongerMatchesItsChecksumIsNotRestored() {
        givenSucceededBackup();
        givenTarget();
        givenSaveEchoes();
        when(storage.exists(Path.of(ARTIFACT))).thenReturn(true);
        when(storage.sha256Of(Path.of(ARTIFACT)))
                .thenReturn("60303ae22b998861bce3b28f33eec1be758a213c86c93c076dbe9f558c11c752");

        runQueuedWork(service.start(BACKUP_ID, TARGET_ID));

        assertThat(lastSaved().getStatus()).isEqualTo(ExecutionStatus.FAILED);
        assertThat(lastSaved().getErrorMessage())
                .contains("does not match the checksum")
                .contains(SHA256)
                .contains("target was not touched");
        verifyNoInteractions(restoreEngine, encryption);
    }

    @Test
    void anArtifactThatIsGoneIsNotRestored() {
        givenSucceededBackup();
        givenTarget();
        givenSaveEchoes();
        when(storage.exists(Path.of(ARTIFACT))).thenReturn(false);

        runQueuedWork(service.start(BACKUP_ID, TARGET_ID));

        assertThat(lastSaved().getErrorMessage()).contains("no longer on disk");
        verifyNoInteractions(restoreEngine);
        verify(storage, never()).sha256Of(any());
    }

    /** Made before checksums were recorded: restored as it always was, unchecked. */
    @Test
    void aBackupWithoutAChecksumIsRestoredWithoutOne() {
        when(backups.findById(BACKUP_ID)).thenReturn(Optional.of(BackupExecution.builder()
                .id(BACKUP_ID).targetId(TARGET_ID).status(ExecutionStatus.SUCCEEDED)
                .startedAt(NOW.minusSeconds(600)).finishedAt(NOW.minusSeconds(500))
                .artifactPath(ARTIFACT).sizeBytes(1024L).build()));
        givenTarget();
        givenSaveEchoes();
        when(storage.exists(Path.of(ARTIFACT))).thenReturn(true);
        when(encryption.decrypt(any())).thenReturn("s3cr3t");

        runQueuedWork(service.start(BACKUP_ID, TARGET_ID));

        assertThat(lastSaved().getStatus()).isEqualTo(ExecutionStatus.SUCCEEDED);
        verify(storage, never()).sha256Of(any());
    }

    // --- repairing after a restart -----------------------------------------

    @Test
    void marksRestoresLeftRunningByAPreviousProcessAsFailed() {
        RestoreExecution stranded =
                RestoreExecution.started(UUID.randomUUID(), BACKUP_ID, TARGET_ID, NOW.minusSeconds(900));
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
                        .succeeded(ARTIFACT, 1024L, SHA256, NOW.minusSeconds(500))));
    }

    private void givenArtifactIntact() {
        when(storage.exists(Path.of(ARTIFACT))).thenReturn(true);
        when(storage.sha256Of(Path.of(ARTIFACT))).thenReturn(SHA256);
    }

    private void givenTarget() {
        when(targets.findById(TARGET_ID)).thenReturn(Optional.of(DatabaseTarget.builder().engine(com.hoangluongtran0309.dbbackup.core.model.DatabaseEngine.MYSQL)
                .id(TARGET_ID).name("production").host("db.internal").port(3307)
                .databaseName("shop").username("backup").passwordCiphertext("sealed")
                .createdAt(NOW).build()));
    }

    private void givenSaveEchoes() {
        when(restores.save(any())).thenAnswer(call -> call.getArgument(0));
    }

    private void runQueuedWork(UUID restoreId) {
        when(restores.findById(restoreId)).thenReturn(Optional.of(
                RestoreExecution.started(restoreId, BACKUP_ID, TARGET_ID, NOW)));
        queue.forEach(Runnable::run);
    }

    private RestoreExecution lastSaved() {
        verify(restores, Mockito.atLeastOnce()).save(saved.capture());
        return saved.getValue();
    }
}
