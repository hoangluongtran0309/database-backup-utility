package com.hoangluongtran0309.dbbackup.core.port;

import java.io.InputStream;
import java.nio.file.Path;

/**
 * Where backup artifacts live.
 */
public interface StoragePort {

    /**
     * Reserves a location for a new artifact.
     *
     * @param filename a bare file name, with no path separators
     * @return an absolute path whose parent directory exists
     */
    Path locationFor(String filename);

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

    /** Removes an artifact if it is still there. Absent is not an error. */
    void delete(Path artifact);
}
