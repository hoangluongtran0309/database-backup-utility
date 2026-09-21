package com.hoangluongtran0309.dbbackup.adapter.mariadb;

import java.nio.file.Files;
import java.nio.file.Path;

/** Fails startup when one of the configured MariaDB client tools cannot run. */
final class MariaDbBinaries {

    private MariaDbBinaries() {
    }

    static Path require(Path binary, String property) {
        if (!Files.isExecutable(binary)) {
            throw new IllegalStateException(
                    "%s points at '%s', which is not an executable file".formatted(property, binary));
        }
        return binary;
    }
}
