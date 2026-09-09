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

        service.delete(BACKUP_ID);

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

        service.delete(BACKUP_ID);

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

    private void givenSucceeded() {
        when(backups.findById(BACKUP_ID)).thenReturn(Optional.of(
                BackupExecution.started(BACKUP_ID, TARGET_ID, STARTED)
                        .succeeded(PATH, 8192L, STARTED.plusSeconds(60))));
    }
}
