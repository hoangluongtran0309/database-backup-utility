package com.hoangluongtran0309.dbbackup.adapter.sqlserver;

import java.nio.file.Files;
import java.nio.file.Path;

final class SqlServerBinaries {

    private SqlServerBinaries() {
    }

    static Path require(Path binary, String property) {
        if (!Files.isExecutable(binary)) {
            throw new IllegalStateException(
                    "%s points at '%s', which is not an executable file".formatted(property, binary));
        }
        return binary;
    }
}
