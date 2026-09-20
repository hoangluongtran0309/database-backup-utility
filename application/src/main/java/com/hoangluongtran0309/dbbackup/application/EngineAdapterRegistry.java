package com.hoangluongtran0309.dbbackup.application;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
    private final Set<DatabaseEngine> availableEngines;

    public EngineAdapterRegistry(
            List<ConnectionTestPort> connectionTests,
            List<LogicalBackupPort> backupAdapters,
            List<LogicalRestorePort> restoreAdapters) {
        this.connectionTests = index(connectionTests, ConnectionTestPort::engine, "connection test");
        this.backupAdapters = index(backupAdapters, LogicalBackupPort::engine, "logical backup");
        this.restoreAdapters = index(restoreAdapters, LogicalRestorePort::engine, "logical restore");
        validateCompleteSets();
        EnumSet<DatabaseEngine> available = EnumSet.noneOf(DatabaseEngine.class);
        available.addAll(this.connectionTests.keySet());
        available.retainAll(this.backupAdapters.keySet());
        available.retainAll(this.restoreAdapters.keySet());
        this.availableEngines = Set.copyOf(available);
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

    public boolean supports(DatabaseEngine engine) {
        return availableEngines.contains(engine);
    }

    public List<DatabaseEngine> availableEngines() {
        return java.util.Arrays.stream(DatabaseEngine.values()).filter(this::supports).toList();
    }

    private void validateCompleteSets() {
        for (DatabaseEngine engine : DatabaseEngine.values()) {
            int capabilities = (connectionTests.containsKey(engine) ? 1 : 0)
                    + (backupAdapters.containsKey(engine) ? 1 : 0)
                    + (restoreAdapters.containsKey(engine) ? 1 : 0);
            if (capabilities != 0 && capabilities != 3) {
                throw new IllegalStateException(
                        "Engine %s must configure connection test, logical backup and logical restore together"
                                .formatted(engine));
            }
        }
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
