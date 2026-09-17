package com.hoangluongtran0309.dbbackup.application;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import org.springframework.stereotype.Component;

import com.hoangluongtran0309.dbbackup.core.model.DatabaseEngine;
import com.hoangluongtran0309.dbbackup.core.port.ConnectionTestPort;
import com.hoangluongtran0309.dbbackup.core.port.LogicalBackupPort;
import com.hoangluongtran0309.dbbackup.core.port.LogicalRestorePort;

/** Resolves exactly one complete adapter set for each supported engine. */
@Component
public class EngineAdapterRegistry {

    private final Map<DatabaseEngine, ConnectionTestPort> connectionTests;
    private final Map<DatabaseEngine, LogicalBackupPort> backupAdapters;
    private final Map<DatabaseEngine, LogicalRestorePort> restoreAdapters;

    public EngineAdapterRegistry(
            List<ConnectionTestPort> connectionTests,
            List<LogicalBackupPort> backupAdapters,
            List<LogicalRestorePort> restoreAdapters) {
        this.connectionTests = index(connectionTests, ConnectionTestPort::engine, "connection test");
        this.backupAdapters = index(backupAdapters, LogicalBackupPort::engine, "logical backup");
        this.restoreAdapters = index(restoreAdapters, LogicalRestorePort::engine, "logical restore");
    }

    public ConnectionTestPort connectionTestFor(DatabaseEngine engine) {
        return require(connectionTests, engine, "connection test");
    }

    public LogicalBackupPort backupFor(DatabaseEngine engine) {
        return require(backupAdapters, engine, "logical backup");
    }

    public LogicalRestorePort restoreFor(DatabaseEngine engine) {
        return require(restoreAdapters, engine, "logical restore");
    }

    private static <T> T require(Map<DatabaseEngine, T> adapters, DatabaseEngine engine, String capability) {
        T adapter = adapters.get(engine);
        if (adapter == null) {
            throw new IllegalStateException("No %s adapter is configured for %s".formatted(capability, engine));
        }
        return adapter;
    }

    private static <T> Map<DatabaseEngine, T> index(
            List<T> adapters, Function<T, DatabaseEngine> engineOf, String capability) {
        Map<DatabaseEngine, T> indexed = new EnumMap<>(DatabaseEngine.class);
        for (T adapter : adapters) {
            DatabaseEngine engine = engineOf.apply(adapter);
            if (indexed.put(engine, adapter) != null) {
                throw new IllegalStateException(
                        "More than one %s adapter is configured for %s".formatted(capability, engine));
            }
        }
        return Map.copyOf(indexed);
    }
}
