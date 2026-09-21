package com.hoangluongtran0309.dbbackup.adapter.sqlserver;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.EnumSet;
import java.util.Set;

/** A short-lived, owner-only response file that keeps SQL credentials out of argv. */
final class SqlPackageResponseFile {

    private static final Set<PosixFilePermission> OWNER_ONLY =
            EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);

    private SqlPackageResponseFile() {
    }

    @FunctionalInterface
    interface Operation<T> {
        T run(Path responseFile);
    }

    static <T> T use(String passwordParameter, String password, Operation<T> operation) {
        if (!passwordParameter.equals("SourcePassword") && !passwordParameter.equals("TargetPassword")) {
            throw new IllegalArgumentException("Unsupported SqlPackage password parameter: " + passwordParameter);
        }
        if (password.indexOf('\r') >= 0 || password.indexOf('\n') >= 0 || password.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("SQL Server passwords must not contain CR, LF, or NUL");
        }
        Path response = create(passwordParameter, password);
        Throwable failure = null;
        try {
            return operation.run(response);
        } catch (RuntimeException | Error e) {
            failure = e;
            throw e;
        } finally {
            try {
                Files.delete(response);
            } catch (IOException e) {
                ResponseFileException cleanup = new ResponseFileException(
                        "Could not remove the temporary SqlPackage response file '" + response + "'", e);
                if (failure != null) {
                    failure.addSuppressed(cleanup);
                } else {
                    throw cleanup;
                }
            }
        }
    }

    private static Path create(String passwordParameter, String password) {
        Path response = null;
        try {
            response = Files.createTempFile(
                    "dbbackup-sqlpackage-", ".rsp", PosixFilePermissions.asFileAttribute(OWNER_ONLY));
            if (!Files.getPosixFilePermissions(response).equals(OWNER_ONLY)) {
                throw new IOException("the temporary file is not owner-only");
            }
            String argument = "/%s:%s".formatted(passwordParameter, password);
            Files.writeString(response, quoteArgument(argument) + System.lineSeparator(), StandardCharsets.UTF_8);
            return response;
        } catch (IOException | UnsupportedOperationException e) {
            if (response != null) {
                try {
                    Files.deleteIfExists(response);
                } catch (IOException cleanup) {
                    e.addSuppressed(cleanup);
                }
            }
            throw new ResponseFileException("Could not create a secure SqlPackage response file", e);
        }
    }

    /** Quotes one token using SqlPackage argument-file escaping, not shell or argv escaping. */
    private static String quoteArgument(String value) {
        StringBuilder quoted = new StringBuilder(value.length() + 2).append('"');
        for (int i = 0; i < value.length(); i++) {
            char current = value.charAt(i);
            if (current == '"') {
                // SqlPackage's response-file lexer preserves a quote only as backslash + two quotes.
                quoted.append('\\').append('"').append('"');
            } else {
                quoted.append(current);
            }
        }
        return quoted.append('"').toString();
    }

    static final class ResponseFileException extends RuntimeException {
        ResponseFileException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
