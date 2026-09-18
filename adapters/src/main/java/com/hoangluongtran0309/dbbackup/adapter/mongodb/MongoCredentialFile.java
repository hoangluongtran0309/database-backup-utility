package com.hoangluongtran0309.dbbackup.adapter.mongodb;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.EnumSet;
import java.util.Set;

/** A short-lived, owner-only config file used to keep MongoDB passwords out of argv. */
final class MongoCredentialFile {

    private static final Set<PosixFilePermission> OWNER_ONLY =
            EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);

    private MongoCredentialFile() {
    }

    @FunctionalInterface
    interface Operation<T> {
        T run(Path configFile);
    }

    static <T> T use(String password, Operation<T> operation) {
        Path config = create(password);
        Throwable failure = null;
        try {
            return operation.run(config);
        } catch (RuntimeException | Error e) {
            failure = e;
            throw e;
        } finally {
            try {
                Files.delete(config);
            } catch (IOException e) {
                CredentialFileException cleanup = new CredentialFileException(
                        "Could not remove the temporary MongoDB credential file '" + config + "'", e);
                if (failure != null) {
                    failure.addSuppressed(cleanup);
                } else {
                    throw cleanup;
                }
            }
        }
    }

    private static Path create(String password) {
        Path config = null;
        try {
            config = Files.createTempFile(
                    "dbbackup-mongodb-", ".yml", PosixFilePermissions.asFileAttribute(OWNER_ONLY));
            if (!Files.getPosixFilePermissions(config).equals(OWNER_ONLY)) {
                throw new IOException("the temporary file is not owner-only");
            }
            Files.writeString(config, "password: \"" + yamlDoubleQuoted(password) + "\"\n",
                    StandardCharsets.UTF_8);
            return config;
        } catch (IOException | UnsupportedOperationException e) {
            if (config != null) {
                try {
                    Files.deleteIfExists(config);
                } catch (IOException cleanup) {
                    e.addSuppressed(cleanup);
                }
            }
            throw new CredentialFileException("Could not create a secure MongoDB credential file", e);
        }
    }

    /** YAML double-quoted strings use the same escapes for these characters. */
    private static String yamlDoubleQuoted(String value) {
        StringBuilder escaped = new StringBuilder(value.length());
        value.codePoints().forEach(codePoint -> {
            switch (codePoint) {
                case '\\' -> escaped.append("\\\\");
                case '"' -> escaped.append("\\\"");
                case '\n' -> escaped.append("\\n");
                case '\r' -> escaped.append("\\r");
                case '\t' -> escaped.append("\\t");
                default -> {
                    if (Character.isISOControl(codePoint)) {
                        escaped.append("\\u%04x".formatted(codePoint));
                    } else {
                        escaped.appendCodePoint(codePoint);
                    }
                }
            }
        });
        return escaped.toString();
    }

    static final class CredentialFileException extends RuntimeException {
        CredentialFileException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
