package com.hoangluongtran0309.dbbackup.adapter.mysql;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Checks that a configured MySQL binary is actually runnable.
 *
 * <p>Called from adapter constructors so a wrong path stops the application at
 * startup. This tool can do nothing useful without these binaries, and
 * discovering that at the first backup — when someone is relying on it — is
 * the wrong time.
 */
final class MysqlBinaries {

    private MysqlBinaries() {
    }

    static Path require(Path binary, String property) {
        if (!Files.isExecutable(binary)) {
            throw new IllegalStateException(
                    "%s points at '%s', which is not an executable file".formatted(property, binary));
        }
        return binary;
    }
}
