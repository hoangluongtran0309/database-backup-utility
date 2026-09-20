package com.hoangluongtran0309.dbbackup.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.hoangluongtran0309.dbbackup.core.model.DatabaseEngine;
import com.hoangluongtran0309.dbbackup.core.port.ConnectionTestPort;
import com.hoangluongtran0309.dbbackup.core.port.LogicalBackupPort;
import com.hoangluongtran0309.dbbackup.core.port.LogicalRestorePort;

class EngineAdapterRegistryTest {

    @Test
    void routesEachCapabilityByEngine() {
        ConnectionTestPort connection = mock(ConnectionTestPort.class);
        LogicalBackupPort backup = mock(LogicalBackupPort.class);
        LogicalRestorePort restore = mock(LogicalRestorePort.class);
        when(connection.engine()).thenReturn(DatabaseEngine.POSTGRESQL);
        when(backup.engine()).thenReturn(DatabaseEngine.POSTGRESQL);
        when(restore.engine()).thenReturn(DatabaseEngine.POSTGRESQL);

        EngineAdapterRegistry registry = new EngineAdapterRegistry(
                List.of(connection), List.of(backup), List.of(restore));

        assertThat(registry.connectionTestFor(DatabaseEngine.POSTGRESQL)).isSameAs(connection);
        assertThat(registry.backupFor(DatabaseEngine.POSTGRESQL)).isSameAs(backup);
        assertThat(registry.restoreFor(DatabaseEngine.POSTGRESQL)).isSameAs(restore);
        assertThat(registry.availableEngines()).containsExactly(DatabaseEngine.POSTGRESQL);
        assertThat(registry.supports(DatabaseEngine.ORACLE)).isFalse();
    }

    @Test
    void reportsAMissingCapabilityClearly() {
        EngineAdapterRegistry registry = new EngineAdapterRegistry(List.of(), List.of(), List.of());

        assertThatThrownBy(() -> registry.backupFor(DatabaseEngine.POSTGRESQL))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("logical backup")
                .hasMessageContaining("POSTGRESQL");
    }

    @Test
    void rejectsDuplicateAdaptersAtStartup() {
        LogicalBackupPort first = mock(LogicalBackupPort.class);
        LogicalBackupPort second = mock(LogicalBackupPort.class);
        when(first.engine()).thenReturn(DatabaseEngine.MYSQL);
        when(second.engine()).thenReturn(DatabaseEngine.MYSQL);

        assertThatThrownBy(() -> new EngineAdapterRegistry(
                List.of(), List.of(first, second), List.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("More than one logical backup adapter")
                .hasMessageContaining("MYSQL");
    }

    @Test
    void rejectsAPartiallyConfiguredEngineAtStartup() {
        LogicalBackupPort backup = mock(LogicalBackupPort.class);
        when(backup.engine()).thenReturn(DatabaseEngine.ORACLE);

        assertThatThrownBy(() -> new EngineAdapterRegistry(List.of(), List.of(backup), List.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ORACLE")
                .hasMessageContaining("together");
    }
}
