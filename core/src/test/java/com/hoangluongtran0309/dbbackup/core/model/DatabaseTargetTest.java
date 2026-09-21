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
        return DatabaseTarget.builder().engine(com.hoangluongtran0309.dbbackup.core.model.DatabaseEngine.MYSQL)
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
    void anEditKeepsTheIdentitySchemaAndCreationTime() {
        DatabaseTarget original = valid().build();

        DatabaseTarget edited = original.edited("staging", "db.internal", 3307, "reader", null);

        assertThat(edited.getId()).isEqualTo(original.getId());
        assertThat(edited.getEngine()).isEqualTo(DatabaseEngine.MYSQL);
        assertThat(edited.getDatabaseName()).isEqualTo("shop");
        assertThat(edited.getCreatedAt()).isEqualTo(original.getCreatedAt());
        assertThat(edited.getName()).isEqualTo("staging");
        assertThat(edited.address()).isEqualTo("db.internal:3307/shop");
        assertThat(edited.getUsername()).isEqualTo("reader");
    }

    @Test
    void appliesPostgresqlIdentifierLimits() {
        assertThatThrownBy(() -> valid()
                .engine(DatabaseEngine.POSTGRESQL)
                .port(5432)
                .databaseName("d".repeat(64))
                .build())
                .isInstanceOf(InvalidTargetException.class)
                .extracting("field").isEqualTo("databaseName");

        assertThatThrownBy(() -> valid()
                .engine(DatabaseEngine.POSTGRESQL)
                .port(5432)
                .username("u".repeat(64))
                .build())
                .isInstanceOf(InvalidTargetException.class)
                .extracting("field").isEqualTo("username");
    }

    @Test
    void mariadbUsesItsDefaultPortAndAllowsA128CharacterUsername() {
        DatabaseTarget mariadb = valid()
                .engine(DatabaseEngine.MARIADB)
                .username("u".repeat(128))
                .build();

        assertThat(DatabaseEngine.MARIADB.defaultPort()).isEqualTo(3306);
        assertThat(mariadb.getUsername()).hasSize(128);
        assertThat(mariadb.backupNamespace()).isEqualTo("shop");

        assertThatThrownBy(() -> valid()
                .engine(DatabaseEngine.MARIADB)
                .username("u".repeat(129))
                .build())
                .isInstanceOf(InvalidTargetException.class)
                .extracting("field").isEqualTo("username");
    }

    @Test
    void sqlServerUsesPort1433And128CharacterIdentifiers() {
        DatabaseTarget sqlServer = valid()
                .engine(DatabaseEngine.SQLSERVER)
                .port(1433)
                .databaseName("d".repeat(128))
                .username("u".repeat(128))
                .build();

        assertThat(DatabaseEngine.SQLSERVER.defaultPort()).isEqualTo(1433);
        assertThat(sqlServer.getDatabaseName()).hasSize(128);
        assertThat(sqlServer.getUsername()).hasSize(128);
        assertThat(sqlServer.backupNamespace()).hasSize(128);

        assertThatThrownBy(() -> valid()
                .engine(DatabaseEngine.SQLSERVER)
                .databaseName("d".repeat(129))
                .build())
                .isInstanceOf(InvalidTargetException.class)
                .extracting("field").isEqualTo("databaseName");
        assertThatThrownBy(() -> valid()
                .engine(DatabaseEngine.SQLSERVER)
                .username("u".repeat(129))
                .build())
                .isInstanceOf(InvalidTargetException.class)
                .extracting("field").isEqualTo("username");
    }

    @Test
    void mongodbRequiresItsAuthenticationDatabaseAndAllowsA63CharacterUsername() {
        DatabaseTarget mongo = valid()
                .engine(DatabaseEngine.MONGODB)
                .port(27017)
                .username("u".repeat(63))
                .authenticationDatabase(" admin ")
                .build();

        assertThat(mongo.getAuthenticationDatabase()).isEqualTo("admin");
        assertThat(mongo.backupNamespace()).isEqualTo("shop");
        assertThatThrownBy(() -> valid()
                .engine(DatabaseEngine.MONGODB)
                .port(27017)
                .authenticationDatabase(" ")
                .build())
                .isInstanceOf(InvalidTargetException.class)
                .extracting("field").isEqualTo("authenticationDatabase");
    }

    @Test
    void sqlTargetsRejectAMongodbAuthenticationDatabase() {
        assertThatThrownBy(() -> valid().authenticationDatabase("admin").build())
                .isInstanceOf(InvalidTargetException.class)
                .extracting("field").isEqualTo("authenticationDatabase");
    }

    @Test
    void oracleUsesTheLoginSchemaAsItsBackupNamespaceAndNormalizesIdentifiers() {
        DatabaseTarget oracle = valid()
                .engine(DatabaseEngine.ORACLE)
                .port(1521)
                .databaseName("FREEPDB1")
                .username("app_owner")
                .dataPumpDirectory("dbbackup_pump_dir")
                .build();

        assertThat(oracle.getUsername()).isEqualTo("APP_OWNER");
        assertThat(oracle.getDataPumpDirectory()).isEqualTo("DBBACKUP_PUMP_DIR");
        assertThat(oracle.backupNamespace()).isEqualTo("APP_OWNER");
    }

    @Test
    void oracleRequiresAnUnquotedDataPumpDirectoryAndSchemaIdentifier() {
        assertThatThrownBy(() -> valid()
                .engine(DatabaseEngine.ORACLE)
                .port(1521)
                .databaseName("FREEPDB1")
                .username("APP OWNER")
                .dataPumpDirectory("DBBACKUP_PUMP_DIR")
                .build())
                .isInstanceOf(InvalidTargetException.class)
                .extracting("field").isEqualTo("username");

        assertThatThrownBy(() -> valid()
                .engine(DatabaseEngine.ORACLE)
                .port(1521)
                .databaseName("FREEPDB1")
                .username("APP_OWNER")
                .build())
                .isInstanceOf(InvalidTargetException.class)
                .extracting("field").isEqualTo("dataPumpDirectory");
    }

    @Test
    void nonOracleTargetsRejectADataPumpDirectory() {
        assertThatThrownBy(() -> valid().dataPumpDirectory("DBBACKUP_PUMP_DIR").build())
                .isInstanceOf(InvalidTargetException.class)
                .extracting("field").isEqualTo("dataPumpDirectory");
    }

    @Test
    void sqliteUsesOnlyARelativeDatabaseFile() {
        DatabaseTarget target = sqlite().databaseName(" apps/shop.db ").build();

        assertThat(target.getEngine()).isEqualTo(DatabaseEngine.SQLITE);
        assertThat(target.getDatabaseName()).isEqualTo("apps/shop.db");
        assertThat(target.address()).isEqualTo("apps/shop.db");
        assertThat(target.artifactBaseName()).isEqualTo("shop.db");
        assertThat(target.getHost()).isNull();
        assertThat(target.getPort()).isNull();
        assertThat(target.getUsername()).isNull();
        assertThat(target.getPasswordCiphertext()).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"/var/data/shop.db", "../shop.db", "apps/../shop.db", "./shop.db", "apps\\shop.db"})
    void sqliteRejectsPathsThatAreNotPortableAndRelative(String path) {
        assertThatThrownBy(() -> sqlite().databaseName(path).build())
                .isInstanceOf(InvalidTargetException.class)
                .extracting("field").isEqualTo("databaseName");
    }

    @Test
    void sqliteRejectsNetworkAndCredentialFields() {
        assertThatThrownBy(() -> sqlite().host("localhost").build())
                .isInstanceOf(InvalidTargetException.class)
                .extracting("field").isEqualTo("host");
        assertThatThrownBy(() -> sqlite().port(1).build())
                .isInstanceOf(InvalidTargetException.class)
                .extracting("field").isEqualTo("port");
        assertThatThrownBy(() -> sqlite().username("backup").build())
                .isInstanceOf(InvalidTargetException.class)
                .extracting("field").isEqualTo("username");
        assertThatThrownBy(() -> sqlite().passwordCiphertext("sealed").build())
                .isInstanceOf(InvalidTargetException.class)
                .extracting("field").isEqualTo("passwordCiphertext");
    }

    @Test
    void sqliteEditOnlyAllowsARenameAndKeepsItsCheck() {
        DatabaseTarget tested = sqlite()
                .lastConnectionCheck(ConnectionCheck.passed(Instant.now()))
                .build();

        DatabaseTarget renamed = tested.edited("archive", null, null, null, null);

        assertThat(renamed.getName()).isEqualTo("archive");
        assertThat(renamed.getDatabaseName()).isEqualTo("shop.db");
        assertThat(renamed.hasBeenTested()).isTrue();
    }

    @Test
    void changingMongoAuthenticationDatabaseDropsTheLastConnectionCheck() {
        DatabaseTarget tested = valid()
                .engine(DatabaseEngine.MONGODB)
                .port(27017)
                .authenticationDatabase("admin")
                .lastConnectionCheck(ConnectionCheck.passed(Instant.now()))
                .build();

        DatabaseTarget edited = tested.edited(
                "production", "127.0.0.1", 27017, "backup", "users", null);

        assertThat(edited.getAuthenticationDatabase()).isEqualTo("users");
        assertThat(edited.hasBeenTested()).isFalse();
    }

    @Test
    void anEditWithoutANewPasswordKeepsTheStoredOne() {
        DatabaseTarget edited = valid().build().edited("production", "127.0.0.1", 3306, "backup", null);

        assertThat(edited.getPasswordCiphertext()).isEqualTo("Zm9vYmFy");
    }

    @Test
    void anEditWithANewPasswordReplacesIt() {
        DatabaseTarget edited = valid().build().edited("production", "127.0.0.1", 3306, "backup", "bmV3");

        assertThat(edited.getPasswordCiphertext()).isEqualTo("bmV3");
    }

    @Test
    void aRenameAloneKeepsTheLastConnectionCheck() {
        DatabaseTarget tested = valid().lastConnectionCheck(ConnectionCheck.passed(Instant.now())).build();

        // Surrounding space on the unchanged fields is not a change either.
        DatabaseTarget renamed = tested.edited("prod", " 127.0.0.1 ", 3306, "backup ", null);

        assertThat(renamed.hasBeenTested()).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"host", "port", "username", "password"})
    void changingHowItConnectsDropsTheLastConnectionCheck(String changed) {
        DatabaseTarget tested = valid().lastConnectionCheck(ConnectionCheck.passed(Instant.now())).build();

        DatabaseTarget edited = tested.edited(
                "production",
                changed.equals("host") ? "10.0.0.5" : "127.0.0.1",
                changed.equals("port") ? 3307 : 3306,
                changed.equals("username") ? "reader" : "backup",
                changed.equals("password") ? "bmV3" : null);

        assertThat(edited.hasBeenTested()).isFalse();
    }

    @Test
    void anEditIsValidatedLikeARegistration() {
        DatabaseTarget original = valid().build();

        assertThatThrownBy(() -> original.edited(" ", "127.0.0.1", 3306, "backup", null))
                .isInstanceOf(InvalidTargetException.class)
                .extracting("field").isEqualTo("name");
        assertThatThrownBy(() -> original.edited("production", "127.0.0.1", 0, "backup", null))
                .isInstanceOf(InvalidTargetException.class)
                .extracting("field").isEqualTo("port");
    }

    @Test
    void rejectsMissingIdAndTimestamp() {
        assertThatThrownBy(() -> valid().id(null).build())
                .isInstanceOf(InvalidTargetException.class);
        assertThatThrownBy(() -> valid().createdAt(null).build())
                .isInstanceOf(InvalidTargetException.class);
    }

    private static DatabaseTarget.DatabaseTargetBuilder sqlite() {
        return DatabaseTarget.builder()
                .engine(DatabaseEngine.SQLITE)
                .id(UUID.randomUUID())
                .name("local shop")
                .databaseName("shop.db")
                .createdAt(Instant.parse("2026-09-09T10:15:30Z"));
    }
}
