package com.hoangluongtran0309.dbbackup.application.backup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.hoangluongtran0309.dbbackup.core.model.BackupExecution;
import com.hoangluongtran0309.dbbackup.core.model.RestoreExecution;
import com.hoangluongtran0309.dbbackup.core.port.BackupExecutionRepository;
import com.hoangluongtran0309.dbbackup.core.port.RestoreExecutionRepository;
import com.hoangluongtran0309.dbbackup.core.port.StoragePort;

@ExtendWith(MockitoExtension.class)
class BackupArtifactServiceTest {

    private static final Instant STARTED = Instant.parse("2026-09-09T10:00:00Z");
    private static final UUID BACKUP_ID = UUID.randomUUID();
    private static final UUID TARGET_ID = UUID.randomUUID();
    private static final String PATH = "/backups/shop_20260909_100000.sql.gz";
    private static final Path ARTIFACT = Path.of(PATH);
    private static final String SHA256 = "9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08";
    private static final String OTHER_SHA256 = "60303ae22b998861bce3b28f33eec1be758a213c86c93c076dbe9f558c11c752";

    @Mock private BackupExecutionRepository backups;
    @Mock private RestoreExecutionRepository restores;
    @Mock private StoragePort storage;

    private BackupArtifactService service;

    @BeforeEach
    void setUp() {
        service = new BackupArtifactService(backups, restores, storage);
    }

    // --- download -----------------------------------------------------------

    @Test
    void downloadsTheArtifactUnderItsOwnFileName() {
        givenSucceeded();
        when(storage.exists(ARTIFACT)).thenReturn(true);
        when(storage.openForReading(ARTIFACT))
                .thenReturn(new ByteArrayInputStream("gz".getBytes(StandardCharsets.UTF_8)));

        BackupArtifactService.ArtifactDownload download = service.download(BACKUP_ID);

        assertThat(download.filename()).isEqualTo("shop_20260909_100000.sql.gz");
        assertThat(download.content()).isNotNull();
    }

    /** The row outlives the file if someone removed it from underneath us. */
    @Test
    void refusesToDownloadAnArtifactThatIsNoLongerOnDisk() {
        givenSucceeded();
        when(storage.exists(ARTIFACT)).thenReturn(false);

        assertThatThrownBy(() -> service.download(BACKUP_ID))
                .isInstanceOf(NoSuchElementException.class)
                .hasMessageContaining("no longer on disk");
        verify(storage, never()).openForReading(any());
    }

    @Test
    void refusesToDownloadFromABackupThatProducedNothing() {
        when(backups.findById(BACKUP_ID)).thenReturn(Optional.of(
                BackupExecution.started(BACKUP_ID, TARGET_ID, STARTED)
                        .failed("mysqldump exited with 2", STARTED.plusSeconds(3))));

        assertThatThrownBy(() -> service.download(BACKUP_ID))
                .isInstanceOf(NoSuchElementException.class)
                .hasMessageContaining("produced no artifact");
    }

    @Test
    void refusesToDownloadAnUnknownBackup() {
        when(backups.findById(BACKUP_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.download(BACKUP_ID))
                .isInstanceOf(NoSuchElementException.class);
    }

    // --- preview ------------------------------------------------------------

    @Test
    void previewReportsTheFileAndHowManyRestoresWouldGoWithIt() {
        givenSucceeded();
        when(storage.exists(ARTIFACT)).thenReturn(true);
        when(restores.countForBackup(BACKUP_ID)).thenReturn(2L);

        BackupArtifactService.DeletionPreview preview = service.previewDeletion(BACKUP_ID);

        assertThat(preview.artifactPresent()).isTrue();
        assertThat(preview.restoreCount()).isEqualTo(2L);
        assertThat(preview.execution().getId()).isEqualTo(BACKUP_ID);
    }

    @Test
    void previewCopesWithAFailedBackupThatHasNoArtifactPath() {
        when(backups.findById(BACKUP_ID)).thenReturn(Optional.of(
                BackupExecution.started(BACKUP_ID, TARGET_ID, STARTED)
                        .failed("boom", STARTED.plusSeconds(1))));
        when(restores.countForBackup(BACKUP_ID)).thenReturn(0L);

        assertThat(service.previewDeletion(BACKUP_ID).artifactPresent()).isFalse();
    }

    // --- delete -------------------------------------------------------------

    /**
     * Restores, then file, then row. Deleting the row first and then failing on
     * the file would strand an artifact on disk with nothing pointing at it.
     */
    @Test
    void deletesRestoreRecordsThenTheFileThenTheRow() {
        givenSucceeded();

        assertThat(service.delete(BACKUP_ID)).as("had an artifact").isTrue();

        InOrder order = inOrder(restores, storage, backups);
        order.verify(restores).deleteForBackup(BACKUP_ID);
        order.verify(storage).delete(ARTIFACT);
        order.verify(backups).deleteById(BACKUP_ID);
    }

    @Test
    void leavesTheRowAloneWhenTheFileCannotBeDeleted() {
        givenSucceeded();
        org.mockito.Mockito.doThrow(new java.io.UncheckedIOException(
                        new java.io.IOException("Permission denied")))
                .when(storage).delete(ARTIFACT);

        assertThatThrownBy(() -> service.delete(BACKUP_ID))
                .isInstanceOf(java.io.UncheckedIOException.class);

        // Still visible, so the operator can try again.
        verify(backups, never()).deleteById(any());
    }

    @Test
    void deletesAFailedBackupThatNeverProducedAFile() {
        when(backups.findById(BACKUP_ID)).thenReturn(Optional.of(
                BackupExecution.started(BACKUP_ID, TARGET_ID, STARTED)
                        .failed("boom", STARTED.plusSeconds(1))));

        assertThat(service.delete(BACKUP_ID)).as("had an artifact").isFalse();

        verify(storage, never()).delete(any());
        verify(backups).deleteById(BACKUP_ID);
    }

    /** The dump is being written to that file right now. */
    @Test
    void refusesToDeleteABackupThatIsStillRunning() {
        when(backups.findById(BACKUP_ID)).thenReturn(Optional.of(
                BackupExecution.started(BACKUP_ID, TARGET_ID, STARTED)));

        assertThatThrownBy(() -> service.delete(BACKUP_ID))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("still running");

        verify(storage, never()).delete(any());
        verify(backups, never()).deleteById(any());
    }

    /** The restore is reading that file, and would record its outcome against a row that is gone. */
    @Test
    void refusesToDeleteABackupThatIsBeingRestored() {
        givenSucceeded();
        when(restores.findRunning()).thenReturn(List.of(
                RestoreExecution.started(UUID.randomUUID(), BACKUP_ID, TARGET_ID, STARTED.plusSeconds(90))));

        assertThatThrownBy(() -> service.delete(BACKUP_ID))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("being restored");

        verify(restores, never()).deleteForBackup(any());
        verify(storage, never()).delete(any());
        verify(backups, never()).deleteById(any());
    }

    // --- several at once ----------------------------------------------------

    @Test
    void deletesEachBackupRestoresThenFileThenRow() {
        BackupExecution first = succeeded(UUID.randomUUID(), "/backups/a.sql.gz", 100L, STARTED);
        BackupExecution second = succeeded(UUID.randomUUID(), "/backups/b.sql.gz", 200L, STARTED.plusSeconds(60));
        when(backups.findAllById(any())).thenReturn(List.of(first, second));

        BackupArtifactService.BulkDeletion done = service.deleteAll(List.of(first.getId(), second.getId()));

        assertThat(done).isEqualTo(new BackupArtifactService.BulkDeletion(2, 2, 0));
        for (BackupExecution execution : List.of(first, second)) {
            InOrder order = inOrder(restores, storage, backups);
            order.verify(restores).deleteForBackup(execution.getId());
            order.verify(storage).delete(Path.of(execution.getArtifactPath()));
            order.verify(backups).deleteById(execution.getId());
        }
    }

    /** Checked for all of them first: a refusal deletes nothing, not "some of them". */
    @Test
    void oneRunningBackupRefusesTheWholeBatch() {
        BackupExecution done = succeeded(UUID.randomUUID(), "/backups/a.sql.gz", 100L, STARTED);
        BackupExecution running = BackupExecution.started(UUID.randomUUID(), TARGET_ID, STARTED.plusSeconds(60));
        when(backups.findAllById(any())).thenReturn(List.of(done, running));

        assertThatThrownBy(() -> service.deleteAll(List.of(done.getId(), running.getId())))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("still running");

        verify(restores, never()).deleteForBackup(any());
        verify(storage, never()).delete(any());
        verify(backups, never()).deleteById(any());
    }

    @Test
    void oneBackupBeingRestoredRefusesTheWholeBatch() {
        BackupExecution first = succeeded(UUID.randomUUID(), "/backups/a.sql.gz", 100L, STARTED);
        BackupExecution second = succeeded(UUID.randomUUID(), "/backups/b.sql.gz", 200L, STARTED.plusSeconds(60));
        when(backups.findAllById(any())).thenReturn(List.of(first, second));
        when(restores.findRunning()).thenReturn(List.of(
                RestoreExecution.started(UUID.randomUUID(), second.getId(), TARGET_ID, STARTED.plusSeconds(90))));

        assertThatThrownBy(() -> service.deleteAll(List.of(first.getId(), second.getId())))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("being restored");

        verify(backups, never()).deleteById(any());
    }

    /** Deleted in another tab, say. Not an error; counted, so the summary can say so. */
    @Test
    void skipsBackupsThatAreAlreadyGone() {
        BackupExecution present = succeeded(UUID.randomUUID(), "/backups/a.sql.gz", 100L, STARTED);
        UUID gone = UUID.randomUUID();
        when(backups.findAllById(any())).thenReturn(List.of(present));

        BackupArtifactService.BulkDeletion done = service.deleteAll(List.of(present.getId(), gone, gone));

        assertThat(done).isEqualTo(new BackupArtifactService.BulkDeletion(1, 1, 1));
        verify(backups).deleteById(present.getId());
    }

    @Test
    void countsOnlyTheArtifactsThatExistedAsRemoved() {
        BackupExecution failed = BackupExecution.started(UUID.randomUUID(), TARGET_ID, STARTED)
                .failed("boom", STARTED.plusSeconds(1));
        BackupExecution good = succeeded(UUID.randomUUID(), "/backups/a.sql.gz", 100L, STARTED.plusSeconds(60));
        when(backups.findAllById(any())).thenReturn(List.of(failed, good));

        assertThat(service.deleteAll(List.of(failed.getId(), good.getId())))
                .isEqualTo(new BackupArtifactService.BulkDeletion(2, 1, 0));
    }

    @Test
    void previewOfSeveralTotalsWhatIsOnDiskNewestFirst() {
        BackupExecution older = succeeded(UUID.randomUUID(), "/backups/a.sql.gz", 100L, STARTED);
        BackupExecution newer = succeeded(UUID.randomUUID(), "/backups/b.sql.gz", 200L, STARTED.plusSeconds(60));
        BackupExecution vanished = succeeded(UUID.randomUUID(), "/backups/c.sql.gz", 400L, STARTED.plusSeconds(120));
        when(backups.findAllById(any())).thenReturn(List.of(older, vanished, newer));
        when(storage.exists(Path.of("/backups/a.sql.gz"))).thenReturn(true);
        when(storage.exists(Path.of("/backups/b.sql.gz"))).thenReturn(true);
        when(storage.exists(Path.of("/backups/c.sql.gz"))).thenReturn(false);
        when(restores.countForBackups(any())).thenReturn(3L);

        BackupArtifactService.BulkDeletionPreview preview =
                service.previewDeletions(List.of(older.getId(), newer.getId(), vanished.getId()));

        assertThat(preview.executions()).containsExactly(vanished, newer, older);
        assertThat(preview.artifactsOnDisk()).isEqualTo(2);
        assertThat(preview.bytesOnDisk()).isEqualTo(300L);
        assertThat(preview.restoreCount()).isEqualTo(3L);
        assertThat(preview.refused()).isFalse();
    }

    @Test
    void previewOfSeveralSaysWhyItWouldBeRefused() {
        BackupExecution running = BackupExecution.started(UUID.randomUUID(), TARGET_ID, STARTED);
        BackupExecution done = succeeded(UUID.randomUUID(), "/backups/a.sql.gz", 100L, STARTED.plusSeconds(60));
        when(backups.findAllById(any())).thenReturn(List.of(running, done));

        BackupArtifactService.BulkDeletionPreview preview =
                service.previewDeletions(List.of(running.getId(), done.getId()));

        assertThat(preview.refused()).isTrue();
        assertThat(preview.refusal()).startsWith("One of these backups is still running");
    }

    // --- automatic retention ----------------------------------------------

    @Test
    void retentionDeletesTheArtifactBeforeTheRowWithoutTouchingRestoreHistory() {
        givenSucceeded();
        when(restores.countForBackup(BACKUP_ID)).thenReturn(0L);

        assertThat(service.deleteForRetention(BACKUP_ID))
                .isEqualTo(BackupArtifactService.RetentionDeletion.DELETED);

        InOrder order = inOrder(storage, backups);
        order.verify(storage).delete(ARTIFACT);
        order.verify(backups).deleteById(BACKUP_ID);
        verify(restores, never()).deleteForBackup(any());
    }

    @Test
    void retentionNeverDeletesABackupWithRestoreHistory() {
        givenSucceeded();
        when(restores.countForBackup(BACKUP_ID)).thenReturn(1L);

        assertThat(service.deleteForRetention(BACKUP_ID))
                .isEqualTo(BackupArtifactService.RetentionDeletion.PROTECTED);

        verify(storage, never()).delete(any());
        verify(backups, never()).deleteById(any());
    }

    @Test
    void retentionTreatsAnAlreadyDeletedCandidateAsHarmless() {
        when(backups.findById(BACKUP_ID)).thenReturn(Optional.empty());

        assertThat(service.deleteForRetention(BACKUP_ID))
                .isEqualTo(BackupArtifactService.RetentionDeletion.ALREADY_GONE);
    }

    // --- verify -------------------------------------------------------------

    @Test
    void anArtifactThatStillHasItsChecksumIsIntact() {
        givenSucceeded();
        when(storage.exists(ARTIFACT)).thenReturn(true);
        when(storage.sha256Of(ARTIFACT)).thenReturn(SHA256);

        BackupArtifactService.Verification result = service.verify(BACKUP_ID);

        assertThat(result.integrity()).isEqualTo(BackupArtifactService.Integrity.INTACT);
        assertThat(result.actual()).isEqualTo(SHA256);
    }

    @Test
    void anArtifactThatChangedIsAMismatchAndSaysBothChecksums() {
        givenSucceeded();
        when(storage.exists(ARTIFACT)).thenReturn(true);
        when(storage.sha256Of(ARTIFACT)).thenReturn(OTHER_SHA256);

        BackupArtifactService.Verification result = service.verify(BACKUP_ID);

        assertThat(result.integrity()).isEqualTo(BackupArtifactService.Integrity.MISMATCH);
        assertThat(result.recorded()).isEqualTo(SHA256);
        assertThat(result.actual()).isEqualTo(OTHER_SHA256);
    }

    @Test
    void anArtifactThatIsGoneIsMissingAndIsNotRead() {
        givenSucceeded();
        when(storage.exists(ARTIFACT)).thenReturn(false);

        assertThat(service.verify(BACKUP_ID).integrity()).isEqualTo(BackupArtifactService.Integrity.MISSING);
        verify(storage, never()).sha256Of(any());
    }

    /** Made before checksums: nothing to compare with, but today's value is still worth showing. */
    @Test
    void aBackupWithoutARecordedChecksumSaysSoAndShowsTheCurrentOne() {
        when(backups.findById(BACKUP_ID)).thenReturn(Optional.of(BackupExecution.builder()
                .id(BACKUP_ID)
                .targetId(TARGET_ID)
                .status(com.hoangluongtran0309.dbbackup.core.model.ExecutionStatus.SUCCEEDED)
                .startedAt(STARTED)
                .finishedAt(STARTED.plusSeconds(60))
                .artifactPath(PATH)
                .sizeBytes(8192L)
                .build()));
        when(storage.exists(ARTIFACT)).thenReturn(true);
        when(storage.sha256Of(ARTIFACT)).thenReturn(OTHER_SHA256);

        BackupArtifactService.Verification result = service.verify(BACKUP_ID);

        assertThat(result.integrity()).isEqualTo(BackupArtifactService.Integrity.NOT_RECORDED);
        assertThat(result.actual()).isEqualTo(OTHER_SHA256);
    }

    @Test
    void verifyingABackupThatProducedNothingSaysSo() {
        when(backups.findById(BACKUP_ID)).thenReturn(Optional.of(
                BackupExecution.started(BACKUP_ID, TARGET_ID, STARTED).failed("denied", STARTED.plusSeconds(1))));

        assertThatThrownBy(() -> service.verify(BACKUP_ID))
                .isInstanceOf(NoSuchElementException.class)
                .hasMessageContaining("no artifact");
    }

    private static BackupExecution succeeded(UUID id, String path, long size, Instant started) {
        return BackupExecution.started(id, TARGET_ID, started).succeeded(path, size, SHA256, started.plusSeconds(30));
    }

    private void givenSucceeded() {
        when(backups.findById(BACKUP_ID)).thenReturn(Optional.of(
                BackupExecution.started(BACKUP_ID, TARGET_ID, STARTED)
                        .succeeded(PATH, 8192L, SHA256, STARTED.plusSeconds(60))));
    }
}
