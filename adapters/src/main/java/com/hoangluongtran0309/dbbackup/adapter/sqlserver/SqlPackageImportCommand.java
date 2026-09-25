package com.hoangluongtran0309.dbbackup.adapter.sqlserver;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

/** Pure SqlPackage import builder shared by normal and isolated restores. */
final class SqlPackageImportCommand {

    private SqlPackageImportCommand() {
    }

    static List<String> build(
            String binary,
            String sourceFile,
            String server,
            String database,
            String username,
            boolean trustServerCertificate,
            Duration connectTimeout,
            String responseFile) {
        return List.of(
                binary,
                "/Action:Import",
                "/SourceFile:" + sourceFile,
                "/TargetServerName:" + server,
                "/TargetDatabaseName:" + database,
                "/TargetUser:" + username,
                "/TargetEncryptConnection:True",
                "/TargetTrustServerCertificate:" + trustServerCertificate,
                "/TargetTimeout:" + Math.max(1, connectTimeout.toSeconds()),
                "/p:CommandTimeout=0",
                "/p:LongRunningCommandTimeout=0",
                "@" + responseFile);
    }

    static List<String> build(
            Path binary,
            Path sourceFile,
            String server,
            String database,
            String username,
            boolean trustServerCertificate,
            Duration connectTimeout,
            Path responseFile) {
        return build(binary.toString(), sourceFile.toString(), server, database, username,
                trustServerCertificate, connectTimeout, responseFile.toString());
    }
}
