package com.hoangluongtran0309.dbbackup.core.model;

/** Database engines with a complete logical backup and restore adapter. */
public enum DatabaseEngine {
    MYSQL("MySQL", 3306),
    POSTGRESQL("PostgreSQL", 5432),
    MONGODB("MongoDB", 27017),
    SQLITE("SQLite", null),
    ORACLE("Oracle", 1521),
    MARIADB("MariaDB", 3306),
    SQLSERVER("SQL Server", 1433);

    private final String displayName;
    private final Integer defaultPort;

    DatabaseEngine(String displayName, Integer defaultPort) {
        this.displayName = displayName;
        this.defaultPort = defaultPort;
    }

    public String displayName() {
        return displayName;
    }

    public Integer defaultPort() {
        return defaultPort;
    }

    public boolean isFileBased() {
        return this == SQLITE;
    }

    public boolean supportsRestoreVerification() {
        return this != ORACLE && this != SQLSERVER;
    }
}
