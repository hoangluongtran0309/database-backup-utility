package com.hoangluongtran0309.dbbackup.core.port;

import java.io.InputStream;
import java.nio.file.Path;
import java.util.UUID;

/**
 * Where backup artifacts live.
 */
public interface StoragePort {

    /**
     * Reserves a location for a new artifact, in a directory of its own.
     *
     * <p>The directory is {@code <targetId>/<executionId>}, the same shape as a
     * remote object key, so two executions never share a file even when their
     * file names are equal — see ADR-033.
     *
     * @param filename a bare file name, with no path separators
     * @return an absolute path whose parent directory exists
     */
    Path locationFor(UUID targetId, UUID executionId, String filename);

    /** Whether the artifact is still on disk. */
    boolean exists(Path artifact);

    /**
     * Opens an artifact for reading. The caller closes the stream.
     *
     * @throws IllegalArgumentException if the path is outside the store
     * @throws java.io.UncheckedIOException if it cannot be opened
     */
    InputStream openForReading(Path artifact);

    /**
     * SHA-256 of the artifact's bytes as stored, in lower-case hex — what
     * {@code sha256sum} prints for the same file.
     *
     * @throws IllegalArgumentException if the path is outside the store
     * @throws java.io.UncheckedIOException if it cannot be read, which
     *         includes it no longer being there
     */
    String sha256Of(Path artifact);

    /**
     * Removes an artifact if it is still there, and the per-execution and
     * per-target directories it leaves empty. Absent is not an error.
     */
    void delete(Path artifact);
}
