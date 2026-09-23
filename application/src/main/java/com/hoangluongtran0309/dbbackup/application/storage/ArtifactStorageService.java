package com.hoangluongtran0309.dbbackup.application.storage;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.NoSuchElementException;
import java.util.UUID;

import org.springframework.stereotype.Service;

import com.hoangluongtran0309.dbbackup.core.model.ArtifactReference;
import com.hoangluongtran0309.dbbackup.core.model.GcsStorageConnection;
import com.hoangluongtran0309.dbbackup.core.model.S3StorageConnection;
import com.hoangluongtran0309.dbbackup.core.model.StorageCredentialMode;
import com.hoangluongtran0309.dbbackup.core.model.StorageProfile;
import com.hoangluongtran0309.dbbackup.core.model.StorageProvider;
import com.hoangluongtran0309.dbbackup.core.port.EncryptionPort;
import com.hoangluongtran0309.dbbackup.core.port.GcsStoragePort;
import com.hoangluongtran0309.dbbackup.core.port.S3StoragePort;
import com.hoangluongtran0309.dbbackup.core.port.StagingStoragePort;
import com.hoangluongtran0309.dbbackup.core.port.StoragePort;
import com.hoangluongtran0309.dbbackup.core.port.StorageProfileRepository;

import lombok.RequiredArgsConstructor;

/** The application-level router for every artifact operation. */
@Service
@RequiredArgsConstructor
public class ArtifactStorageService {
    private final StoragePort local;
    private final StagingStoragePort staging;
    private final S3StoragePort s3;
    private final GcsStoragePort gcs;
    private final StorageProfileRepository profiles;
    private final EncryptionPort encryption;

    /** Keeps local-only use-case tests and embedders source-compatible. */
    public static ArtifactStorageService localOnly(StoragePort local) {
        StagingStoragePort noStaging = new StagingStoragePort() {
            @Override public Path locationFor(UUID operationId, String filename) { return local.locationFor(filename); }
            @Override public long sizeOf(Path path) {
                try { return java.nio.file.Files.size(path); }
                catch (IOException e) { throw new UncheckedIOException(e); }
            }
            @Override public String sha256Of(Path path) { return local.sha256Of(path); }
            @Override public void deleteOperation(UUID operationId) { }
        };
        S3StoragePort noS3 = new S3StoragePort() {
            private UnsupportedOperationException unavailable() { return new UnsupportedOperationException("S3 unavailable"); }
            @Override public void test(S3StorageConnection c) { throw unavailable(); }
            @Override public void upload(S3StorageConnection c, String k, Path p) { throw unavailable(); }
            @Override public boolean exists(S3StorageConnection c, String k) { throw unavailable(); }
            @Override public InputStream openForReading(S3StorageConnection c, String k) { throw unavailable(); }
            @Override public void download(S3StorageConnection c, String k, Path p) { throw unavailable(); }
            @Override public void delete(S3StorageConnection c, String k) { throw unavailable(); }
        };
        GcsStoragePort noGcs = new GcsStoragePort() {
            private UnsupportedOperationException unavailable() { return new UnsupportedOperationException("GCS unavailable"); }
            @Override public void test(GcsStorageConnection c) { throw unavailable(); }
            @Override public void upload(GcsStorageConnection c, String k, Path p) { throw unavailable(); }
            @Override public boolean exists(GcsStorageConnection c, String k) { throw unavailable(); }
            @Override public InputStream openForReading(GcsStorageConnection c, String k) { throw unavailable(); }
            @Override public void download(GcsStorageConnection c, String k, Path p) { throw unavailable(); }
            @Override public void delete(GcsStorageConnection c, String k) { throw unavailable(); }
        };
        StorageProfileRepository noProfiles = new StorageProfileRepository() {
            @Override public StorageProfile save(StorageProfile p) { throw new UnsupportedOperationException(); }
            @Override public java.util.Optional<StorageProfile> findById(UUID id) { return java.util.Optional.empty(); }
            @Override public java.util.List<StorageProfile> findAll() { return java.util.List.of(); }
            @Override public void recordConnectionCheck(UUID id, com.hoangluongtran0309.dbbackup.core.model.ConnectionCheck c) { }
            @Override public void deleteById(UUID id) { }
        };
        return new ArtifactStorageService(local, noStaging, noS3, noGcs, noProfiles, new EncryptionPort() {
            @Override public String encrypt(String plaintext) { return plaintext; }
            @Override public String decrypt(String ciphertext) { return ciphertext; }
        });
    }

    public PreparedWrite prepareWrite(
            UUID storageProfileId, UUID targetId, UUID executionId, String filename) {
        if (storageProfileId == null) {
            Path path = local.locationFor(filename);
            return new PreparedWrite(executionId, path, new ArtifactReference(null, path.toString()), false);
        }
        StorageProfile profile = requireProfile(storageProfileId);
        String key = objectKey(profile.getKeyPrefix(), targetId, executionId, filename);
        return new PreparedWrite(executionId, staging.locationFor(executionId, filename),
                new ArtifactReference(storageProfileId, key), true);
    }

    public ArtifactReference publish(PreparedWrite write) {
        if (write.remote()) {
            StorageProfile profile = requireProfile(write.reference().storageProfileId());
            if (profile.getProvider() == StorageProvider.S3) {
                s3.upload(s3Connection(profile), write.reference().locator(), write.path());
            } else {
                gcs.upload(gcsConnection(profile), write.reference().locator(), write.path());
            }
        }
        return write.reference();
    }

    public PreparedRead materialize(ArtifactReference reference, UUID operationId, String filename) {
        if (reference.isLocal()) {
            return new PreparedRead(operationId, Path.of(reference.locator()), false);
        }
        Path path = staging.locationFor(operationId, filename);
        try {
            StorageProfile profile = requireProfile(reference.storageProfileId());
            if (profile.getProvider() == StorageProvider.S3) {
                s3.download(s3Connection(profile), reference.locator(), path);
            } else {
                gcs.download(gcsConnection(profile), reference.locator(), path);
            }
        } catch (RuntimeException e) {
            staging.deleteOperation(operationId);
            throw e;
        }
        return new PreparedRead(operationId, path, true);
    }

    public boolean exists(ArtifactReference reference) {
        if (reference.isLocal()) return local.exists(Path.of(reference.locator()));
        StorageProfile profile = requireProfile(reference.storageProfileId());
        return profile.getProvider() == StorageProvider.S3
                ? s3.exists(s3Connection(profile), reference.locator())
                : gcs.exists(gcsConnection(profile), reference.locator());
    }

    public InputStream openForReading(ArtifactReference reference) {
        if (reference.isLocal()) return local.openForReading(Path.of(reference.locator()));
        StorageProfile profile = requireProfile(reference.storageProfileId());
        return profile.getProvider() == StorageProvider.S3
                ? s3.openForReading(s3Connection(profile), reference.locator())
                : gcs.openForReading(gcsConnection(profile), reference.locator());
    }

    public String sha256(ArtifactReference reference) {
        if (reference.isLocal()) {
            return local.sha256Of(Path.of(reference.locator()));
        }
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
        try (InputStream input = openForReading(reference)) {
            byte[] buffer = new byte[64 * 1024];
            for (int read; (read = input.read(buffer)) != -1;) digest.update(buffer, 0, read);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    public String sha256(Path stagedFile, boolean staged) {
        return staged ? staging.sha256Of(stagedFile) : local.sha256Of(stagedFile);
    }

    public long size(Path file, boolean staged, long reportedSize) {
        return staged ? staging.sizeOf(file) : reportedSize;
    }

    public void delete(ArtifactReference reference) {
        if (reference.isLocal()) {
            local.delete(Path.of(reference.locator()));
            return;
        }
        StorageProfile profile = requireProfile(reference.storageProfileId());
        if (profile.getProvider() == StorageProvider.S3) {
            s3.delete(s3Connection(profile), reference.locator());
        } else {
            gcs.delete(gcsConnection(profile), reference.locator());
        }
    }

    public void cleanup(UUID operationId) {
        staging.deleteOperation(operationId);
    }

    public void test(StorageProfile profile) {
        if (profile.getProvider() == StorageProvider.S3) s3.test(s3Connection(profile));
        else gcs.test(gcsConnection(profile));
    }

    private StorageProfile requireProfile(UUID id) {
        return profiles.findById(id).orElseThrow(
                () -> new NoSuchElementException("No storage profile with id " + id));
    }

    private S3StorageConnection s3Connection(StorageProfile profile) {
        String secret = profile.getCredentialMode() == StorageCredentialMode.STATIC
                ? encryption.decrypt(profile.getSecretAccessKeyCiphertext()) : null;
        return new S3StorageConnection(profile.getEndpoint(), profile.getRegion(), profile.getBucket(),
                profile.getKeyPrefix(), profile.isPathStyle(), profile.getCredentialMode(),
                profile.getAccessKeyId(), secret);
    }

    private GcsStorageConnection gcsConnection(StorageProfile profile) {
        String json = profile.getCredentialMode() == StorageCredentialMode.SERVICE_ACCOUNT_JSON
                ? encryption.decrypt(profile.getServiceAccountJsonCiphertext()) : null;
        return new GcsStorageConnection(profile.getEndpoint(), profile.getProjectId(), profile.getBucket(),
                profile.getKeyPrefix(), profile.getCredentialMode(), json);
    }

    static String objectKey(String prefix, UUID targetId, UUID executionId, String filename) {
        String safe = filename.replaceAll("[^a-zA-Z0-9._-]", "_");
        String stem = targetId + "/" + executionId + "/" + safe;
        return prefix == null || prefix.isBlank() ? stem : prefix + "/" + stem;
    }

    public final class PreparedWrite implements AutoCloseable {
        private final UUID operationId;
        private final Path path;
        private final ArtifactReference reference;
        private final boolean remote;
        private PreparedWrite(UUID operationId, Path path, ArtifactReference reference, boolean remote) {
            this.operationId = operationId; this.path = path; this.reference = reference; this.remote = remote;
        }
        public Path path() { return path; }
        public ArtifactReference reference() { return reference; }
        public boolean remote() { return remote; }
        @Override public void close() { if (remote) cleanup(operationId); }
    }

    public final class PreparedRead implements AutoCloseable {
        private final UUID operationId;
        private final Path path;
        private final boolean staged;
        private PreparedRead(UUID operationId, Path path, boolean staged) {
            this.operationId = operationId; this.path = path; this.staged = staged;
        }
        public Path path() { return path; }
        public boolean staged() { return staged; }
        @Override public void close() { if (staged) cleanup(operationId); }
    }
}
