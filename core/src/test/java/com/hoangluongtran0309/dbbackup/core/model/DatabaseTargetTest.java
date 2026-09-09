package com.hoangluongtran0309.dbbackup.core.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.hoangluongtran0309.dbbackup.core.exception.InvalidTargetException;

class DatabaseTargetTest {

    private static DatabaseTarget.DatabaseTargetBuilder valid() {
        return DatabaseTarget.builder()
                .id(UUID.randomUUID())
                .name("production")
                .host("127.0.0.1")
                .port(3306)
                .databaseName("shop")
                .username("backup")
                .passwordCiphertext("Zm9vYmFy")
                .createdAt(Instant.parse("2026-09-09T10:15:30Z"));
    }

    @Test
    void buildsWithValidValues() {
        DatabaseTarget target = valid().build();

        assertThat(target.getName()).isEqualTo("production");
        assertThat(target.getPort()).isEqualTo(3306);
    }

    @Test
    void addressCombinesHostPortAndSchema() {
        assertThat(valid().build().address()).isEqualTo("127.0.0.1:3306/shop");
    }

    @Test
    void trimsSurroundingWhitespaceSoNamesCannotDifferInvisibly() {
        DatabaseTarget target = valid().name("  production  ").host(" db.internal ").build();

        assertThat(target.getName()).isEqualTo("production");
        assertThat(target.getHost()).isEqualTo("db.internal");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   "})
    void rejectsBlankName(String blank) {
        assertThatThrownBy(() -> valid().name(blank).build())
                .isInstanceOf(InvalidTargetException.class)
                .hasMessage("Name is required")
                .extracting("field").isEqualTo("name");
    }

    @Test
    void rejectsNullName() {
        assertThatThrownBy(() -> valid().name(null).build())
                .isInstanceOf(InvalidTargetException.class)
                .extracting("field").isEqualTo("name");
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1, 65536})
    void rejectsPortOutsideTheTcpRange(int port) {
        assertThatThrownBy(() -> valid().port(port).build())
                .isInstanceOf(InvalidTargetException.class)
                .hasMessage("Port must be between 1 and 65535")
                .extracting("field").isEqualTo("port");
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 3306, 65535})
    void acceptsPortsAtTheEdgesOfTheRange(int port) {
        assertThat(valid().port(port).build().getPort()).isEqualTo(port);
    }

    @Test
    void rejectsDatabaseNameLongerThanMysqlAllows() {
        assertThatThrownBy(() -> valid().databaseName("d".repeat(65)).build())
                .isInstanceOf(InvalidTargetException.class)
                .extracting("field").isEqualTo("databaseName");
    }

    @Test
    void rejectsUsernameLongerThanMysqlAllows() {
        assertThatThrownBy(() -> valid().username("u".repeat(33)).build())
                .isInstanceOf(InvalidTargetException.class)
                .extracting("field").isEqualTo("username");
    }

    @Test
    void rejectsMissingCiphertext() {
        assertThatThrownBy(() -> valid().passwordCiphertext(null).build())
                .isInstanceOf(InvalidTargetException.class)
                .extracting("field").isEqualTo("passwordCiphertext");
    }

    @Test
    void rejectsMissingIdAndTimestamp() {
        assertThatThrownBy(() -> valid().id(null).build())
                .isInstanceOf(InvalidTargetException.class);
        assertThatThrownBy(() -> valid().createdAt(null).build())
                .isInstanceOf(InvalidTargetException.class);
    }
}
