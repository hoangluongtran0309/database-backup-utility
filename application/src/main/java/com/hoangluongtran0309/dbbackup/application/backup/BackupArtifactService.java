package com.hoangluongtran0309.dbbackup.application.backup;

import java.io.InputStream;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;

import com.hoangluongtran0309.dbbackup.core.model.BackupExecution;
import com.hoangluongtran0309.dbbackup.core.model.ExecutionStatus;
import com.hoangluongtran0309.dbbackup.core.model.RestoreExecution;
import com.hoangluongtran0309.dbbackup.core.port.BackupExecutionRepository;
import com.hoangluongtran0309.dbbackup.core.port.RestoreExecutionRepository;
import com.hoangluongtran0309.dbbackup.core.port.RestoreVerificationExecutionRepository;
import com.hoangluongtran0309.dbbackup.application.storage.ArtifactStorageService;
import com.hoangluongtran0309.dbbackup.core.model.ArtifactReference;


/**
 * Getting a backup artifact out of the tool, checking it is still what was
 * written, and getting rid of it.
 */
@Service
public class BackupArtifactService {

    private static final Logger log = LoggerFactory.getLogger(BackupArtifactService.class);

    /** An open stream over an artifact. The caller closes it. */
    public record ArtifactDownload(String filename, InputStream content) {
    }

    /** What deleting a backup would take with it. */
    public record DeletionPreview(BackupExecution execution, boolean artifactPresent, long restoreCount) {
    }

    /**
     * What deleting several backups together would take with them.
     *
     * @param executions   the ones that still exist, newest first
     * @param bytesOnDisk  the size of the artifacts still on disk, as stored
     * @param refusal      why they cannot be deleted right now, or null if
     *                     they can
     */
    public record BulkDeletionPreview(
            List<BackupExecution> executions,
            int artifactsOnDisk,
            long bytesOnDisk,
            long restoreCount,
            String refusal) {

        public boolean refused() {
            return refusal != null;
        }
    }

    /**
     * What deleting several backups together did.
     *
     * @param alreadyGone asked for, but no longer there — deleted in another
     *                    tab, say
     */
    public record BulkDeletion(int deleted, int artifactsRemoved, int alreadyGone) {
    }

    /** Outcome of the non-cascading deletion path used only by retention. */
    public enum RetentionDeletion {
        DELETED,
        ALREADY_GONE,
        PROTECTED
    }

    private static final Comparator<BackupExecution> NEWEST_FIRST = Comparator
            .comparing(BackupExecution::getStartedAt).reversed()
            .thenComparing(BackupExecution::getId, Comparator.reverseOrder());

    /** What a verification found. */
    public enum Integrity {
        /** The file on disk has the checksum recorded when it was written. */
        INTACT,
        /** It does not: the file has changed since. */
        MISMATCH,
        /** The file is gone. */
        MISSING,
        /** The backup predates checksums, so there is nothing to compare with. */
        NOT_RECORDED
    }

    /**
     * @param recorded the checksum stored with the backup, or null if none was
     * @param actual   the checksum of the file as it is now, or null if missing
     */
    public record Verification(Integrity integrity, String recorded, String actual) {
    }

    private final BackupExecutionRepository backups;
    private final RestoreExecutionRepository restores;
    private final RestoreVerificationExecutionRepository verifications;
    private final ArtifactStorageService storage;

    @Autowired
    public BackupArtifactService(BackupExecutionRepository backups, RestoreExecutionRepository restores,
            RestoreVerificationExecutionRepository verifications, ArtifactStorageService storage) {
        this.backups = backups; this.restores = restores; this.verifications = verifications; this.storage = storage;
    }

    public BackupArtifactService(BackupExecutionRepository backups, RestoreExecutionRepository restores,
            ArtifactStorageService storage) {
        this(backups, restores, noVerifications(), storage);
    }

    public BackupArtifactService(BackupExecutionRepository backups, RestoreExecutionRepository restores,
            com.hoangluongtran0309.dbbackup.core.port.StoragePort storage) {
        this(backups, restores, noVerifications(), ArtifactStorageService.localOnly(storage));
    }

    /**
     * @throws NoSuchElementException if the backup, or its artifact, is not there
     */
    public ArtifactDownload download(UUID executionId) {
        BackupExecution execution = require(executionId);
        ArtifactReference artifact = artifactOf(execution);

        if (!storage.exists(artifact)) {
            // The row outlives the file if someone removed it from underneath
            // us. Saying so beats a stack trace or an empty download.
            throw new NoSuchElementException(
                    "The artifact for this backup is no longer available: " + artifact.locator());
        }
        return new ArtifactDownload(Path.of(artifact.locator()).getFileName().toString(), storage.openForReading(artifact));
    }

    /**
     * Re-reads the artifact and compares it with the checksum recorded when it
     * was written. Reads the whole file, so it takes as long as the disk does.
     *
     * <p>A backup without a recorded checksum still has its file read: the
     * checksum it has now is worth showing, to compare against a copy kept
     * elsewhere.
     *
     * @throws NoSuchElementException if the backup is not there, or never
     *         produced an artifact
     */
    public Verification verify(UUID executionId) {
        BackupExecution execution = require(executionId);
        ArtifactReference artifact = artifactOf(execution);

        if (!storage.exists(artifact)) {
            return new Verification(Integrity.MISSING, execution.getSha256(), null);
        }
        String actual = storage.sha256(artifact);
        if (!execution.hasChecksum()) {
            return new Verification(Integrity.NOT_RECORDED, null, actual);
        }
        return new Verification(
                execution.getSha256().equals(actual) ? Integrity.INTACT : Integrity.MISMATCH,
                execution.getSha256(),
                actual);
    }

    /** Whether a backup's artifact is still on disk; false if it never had one. */
    public boolean isOnDisk(BackupExecution execution) {
        return execution.getArtifactLocator() != null && storage.exists(artifactOf(execution));
    }

    public DeletionPreview previewDeletion(UUID executionId) {
        BackupExecution execution = require(executionId);
        return new DeletionPreview(execution, isOnDisk(execution), restores.countForBackup(executionId));
    }

    /**
     * The backups among {@code executionIds} that still exist, and what
     * deleting them together would take with them. Unknown ids are skipped.
     */
    public BulkDeletionPreview previewDeletions(Collection<UUID> executionIds) {
        List<BackupExecution> found = backups.findAllById(new LinkedHashSet<>(executionIds)).stream()
                .sorted(NEWEST_FIRST)
                .toList();

        int artifactsOnDisk = 0;
        long bytesOnDisk = 0;
        for (BackupExecution execution : found) {
            if (isOnDisk(execution)) {
                artifactsOnDisk++;
                bytesOnDisk += execution.getSizeBytes() == null ? 0 : execution.getSizeBytes();
            }
        }
        return new BulkDeletionPreview(
                found,
                artifactsOnDisk,
                bytesOnDisk,
                restores.countForBackups(found.stream().map(BackupExecution::getId).toList()),
                refusal(found));
    }

    /**
     * Removes a backup: its restore records, then its file, then its row.
     *
     * <p>That order is deliberate. Deleting the row first and then failing to
     * delete the file would leave an artifact on disk with nothing in the
     * database pointing at it — invisible, and impossible to find later except
     * by hand. This way a failure leaves a visible row whose file is already
     * gone, which the operator can simply delete again.
     *
     * @return whether the backup had an artifact — false for one that failed
     *         before producing a file, so that only its record went
     * @throws IllegalStateException if the backup is still running, or is
     *         being restored
     */
    public boolean delete(UUID executionId) {
        BackupExecution execution = require(executionId);
        refuseIfBusy(List.of(execution));
        return remove(execution);
    }

    /**
     * Removes several backups, each exactly as {@link #delete(UUID)} would.
     *
     * <p>All of them are checked before any is touched, so a refusal deletes
     * nothing. After that they go one at a time: a failure partway leaves the
     * rest, still listed, to be deleted again (ADR-015).
     *
     * @throws IllegalStateException if any of them is still running, or is
     *         being restored
     */
    public BulkDeletion deleteAll(Collection<UUID> executionIds) {
        Set<UUID> asked = new LinkedHashSet<>(executionIds);
        List<BackupExecution> found = backups.findAllById(asked);
        refuseIfBusy(found);

        int artifactsRemoved = 0;
        for (BackupExecution execution : found) {
            if (remove(execution)) {
                artifactsRemoved++;
            }
        }
        return new BulkDeletion(found.size(), artifactsRemoved, asked.size() - found.size());
    }

    /**
     * Deletes one successful backup only when it still has no restore history.
     * Unlike operator-confirmed deletion, this path never deletes restore rows.
     * The caller holds {@link BackupActivityGuard} so a restore cannot be
     * accepted between this check and deletion.
     */
    public RetentionDeletion deleteForRetention(UUID executionId) {
        BackupExecution execution = backups.findById(executionId).orElse(null);
        if (execution == null) {
            return RetentionDeletion.ALREADY_GONE;
        }
        if (execution.getStatus() != ExecutionStatus.SUCCEEDED
                || restores.countForBackup(executionId) > 0
                || verifications.existsRunningForBackup(executionId)) {
            return RetentionDeletion.PROTECTED;
        }
        if (execution.getArtifactLocator() != null) {
            storage.delete(artifactOf(execution));
        }
        backups.deleteById(executionId);
        log.info("Retention deleted backup {} and its artifact {}", executionId, execution.getArtifactLocator());
        return RetentionDeletion.DELETED;
    }

    private boolean remove(BackupExecution execution) {
        boolean hadArtifact = execution.getArtifactLocator() != null;
        restores.deleteForBackup(execution.getId());
        if (hadArtifact) {
            storage.delete(artifactOf(execution));
        }
        backups.deleteById(execution.getId());
        log.info("Deleted backup {} and its artifact {}", execution.getId(), execution.getArtifactLocator());
        return hadArtifact;
    }

    private void refuseIfBusy(List<BackupExecution> executions) {
        String refusal = refusal(executions);
        if (refusal != null) {
            throw new IllegalStateException(refusal);
        }
    }

    /**
     * Why these cannot be deleted now, or null if they can. A running backup
     * is still writing its file; a running restore is still reading it, and
     * would go on to record its outcome against a backup that is gone.
     */
    private String refusal(List<BackupExecution> executions) {
        String which = executions.size() == 1 ? "This backup" : "One of these backups";
        if (executions.stream().anyMatch(e -> e.getStatus() == ExecutionStatus.RUNNING)) {
            return which + " is still running. Wait for it to finish before deleting it.";
        }
        Set<UUID> beingRestored = restores.findRunning().stream()
                .map(RestoreExecution::getBackupExecutionId)
                .collect(Collectors.toSet());
        if (executions.stream().anyMatch(e -> beingRestored.contains(e.getId()))) {
            return which + " is being restored right now. Wait for the restore to finish before deleting it.";
        }
        if (executions.stream().anyMatch(e -> verifications.existsRunningForBackup(e.getId()))) {
            return which + " is being restore-verified right now. Wait for verification to finish before deleting it.";
        }
        return null;
    }

    private static RestoreVerificationExecutionRepository noVerifications() {
        return new RestoreVerificationExecutionRepository() {
            @Override public com.hoangluongtran0309.dbbackup.core.model.RestoreVerificationExecution save(
                    com.hoangluongtran0309.dbbackup.core.model.RestoreVerificationExecution value) {
                throw new UnsupportedOperationException();
            }
            @Override public java.util.Optional<com.hoangluongtran0309.dbbackup.core.model.RestoreVerificationExecution>
                    findById(UUID id) { return java.util.Optional.empty(); }
            @Override public java.util.List<com.hoangluongtran0309.dbbackup.core.model.RestoreVerificationExecution>
                    findForBackupNewestFirst(UUID id) { return java.util.List.of(); }
            @Override public java.util.Optional<com.hoangluongtran0309.dbbackup.core.model.RestoreVerificationExecution>
                    findLatestForBackup(UUID id) { return java.util.Optional.empty(); }
            @Override public java.util.List<com.hoangluongtran0309.dbbackup.core.model.RestoreVerificationExecution>
                    findRunning() { return java.util.List.of(); }
            @Override public boolean existsRunningForBackup(UUID id) { return false; }
        };
    }

    private BackupExecution require(UUID executionId) {
        return backups.findById(executionId)
                .orElseThrow(() -> new NoSuchElementException("No backup execution with id " + executionId));
    }

    private static ArtifactReference artifactOf(BackupExecution execution) {
        if (execution.getArtifactLocator() == null) {
            throw new NoSuchElementException(
                    "That backup produced no artifact — it is %s".formatted(execution.getStatus()));
        }
        return new ArtifactReference(execution.getStorageProfileId(), execution.getArtifactLocator());
    }
}
