package com.hoangluongtran0309.dbbackup.core.model;

/** Database engines with a complete logical backup and restore adapter. */
public enum DatabaseEngine {
    MYSQL("MySQL", 3306),
    POSTGRESQL("PostgreSQL", 5432);

    private final String displayName;
    private final int defaultPort;

    DatabaseEngine(String displayName, int defaultPort) {
        this.displayName = displayName;
        this.defaultPort = defaultPort;
    }

    public String displayName() {
        return displayName;
    }

    public int defaultPort() {
        return defaultPort;
    }
}
