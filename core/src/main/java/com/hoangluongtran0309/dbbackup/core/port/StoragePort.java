package com.hoangluongtran0309.dbbackup.core.port;

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

    /** Removes an artifact if it is still there. Absent is not an error. */
    void delete(Path artifact);
}
