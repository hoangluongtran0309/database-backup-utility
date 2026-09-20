package com.hoangluongtran0309.dbbackup.adapter.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import com.hoangluongtran0309.dbbackup.adapter.TestAdaptersApplication;
import com.hoangluongtran0309.dbbackup.core.exception.DuplicateTargetNameException;
import com.hoangluongtran0309.dbbackup.core.exception.TargetInUseException;
import com.hoangluongtran0309.dbbackup.core.model.BackupExecution;
import com.hoangluongtran0309.dbbackup.core.model.ConnectionCheck;
import com.hoangluongtran0309.dbbackup.core.model.DatabaseEngine;
import com.hoangluongtran0309.dbbackup.core.model.DatabaseTarget;
import com.hoangluongtran0309.dbbackup.core.port.BackupExecutionRepository;
import com.hoangluongtran0309.dbbackup.core.port.DatabaseTargetRepository;

/**
 * Real PostgreSQL, real Flyway, real unique index. H2 would agree with none of
 * the three things this class actually checks: the functional index, the
 * driver's constraint message, and Hibernate's schema validation.
 */
@SpringBootTest(classes = TestAdaptersApplication.class)
@Testcontainers
@ExtendWith(OutputCaptureExtension.class)
class DatabaseTargetRepositoryAdapterIT {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired
    private DatabaseTargetRepository repository;

    @Autowired
    private BackupExecutionRepository backups;

    @Test
    void savesAndReadsBackEveryField() {
        DatabaseTarget saved = repository.save(target("production", "shop"));

        DatabaseTarget found = repository.findById(saved.getId()).orElseThrow();
        assertThat(found.getEngine()).isEqualTo(DatabaseEngine.MYSQL);
        assertThat(found.getName()).isEqualTo("production");
        assertThat(found.getHost()).isEqualTo("127.0.0.1");
        assertThat(found.getPort()).isEqualTo(3306);
        assertThat(found.getDatabaseName()).isEqualTo("shop");
        assertThat(found.getUsername()).isEqualTo("backup");
        assertThat(found.getPasswordCiphertext()).isEqualTo("Y2lwaGVydGV4dA==");
        assertThat(found.getCreatedAt()).isEqualTo(Instant.parse("2026-09-09T10:15:30Z"));
    }

    @Test
    void savesAPostgresqlTargetWithItsLongerUsername() {
        DatabaseTarget postgres = target("analytics", "warehouse").toBuilder()
                .engine(DatabaseEngine.POSTGRESQL)
                .port(5432)
                .username("u".repeat(63))
                .build();

        DatabaseTarget found = repository.findById(repository.save(postgres).getId()).orElseThrow();

        assertThat(found.getEngine()).isEqualTo(DatabaseEngine.POSTGRESQL);
        assertThat(found.getUsername()).hasSize(63);
    }

    @Test
    void savesAMongodbTargetWithItsAuthenticationDatabase() {
        DatabaseTarget mongo = target("documents", "shop").toBuilder()
                .engine(DatabaseEngine.MONGODB)
                .port(27017)
                .authenticationDatabase("admin")
                .build();

        DatabaseTarget found = repository.findById(repository.save(mongo).getId()).orElseThrow();

        assertThat(found.getEngine()).isEqualTo(DatabaseEngine.MONGODB);
        assertThat(found.getAuthenticationDatabase()).isEqualTo("admin");
    }

    @Test
    void savesASqliteTargetWithoutNetworkCredentials() {
        DatabaseTarget sqlite = DatabaseTarget.builder()
                .engine(DatabaseEngine.SQLITE)
                .id(UUID.randomUUID())
                .name("local shop")
                .databaseName("apps/shop.db")
                .createdAt(Instant.parse("2026-09-09T10:15:30Z"))
                .build();

        DatabaseTarget found = repository.findById(repository.save(sqlite).getId()).orElseThrow();

        assertThat(found.getEngine()).isEqualTo(DatabaseEngine.SQLITE);
        assertThat(found.getDatabaseName()).isEqualTo("apps/shop.db");
        assertThat(found.getHost()).isNull();
        assertThat(found.getPort()).isNull();
        assertThat(found.getUsername()).isNull();
        assertThat(found.getPasswordCiphertext()).isNull();
    }

    @Test
    void savesAnOracleTargetWithItsServiceSchemaAndDirectoryObject() {
        DatabaseTarget oracle = target("orders oracle", "FREEPDB1").toBuilder()
                .engine(DatabaseEngine.ORACLE)
                .port(1521)
                .username("A".repeat(128))
                .dataPumpDirectory("DBBACKUP_PUMP_DIR")
                .build();

        DatabaseTarget found = repository.findById(repository.save(oracle).getId()).orElseThrow();

        assertThat(found.getEngine()).isEqualTo(DatabaseEngine.ORACLE);
        assertThat(found.getDatabaseName()).isEqualTo("FREEPDB1");
        assertThat(found.getUsername()).hasSize(128);
        assertThat(found.getDataPumpDirectory()).isEqualTo("DBBACKUP_PUMP_DIR");
    }

    @Test
    void findByIdIsEmptyForAnUnknownId() {
        assertThat(repository.findById(UUID.randomUUID())).isEmpty();
    }

    @Test
    void ordersByNameIgnoringCase() {
        repository.save(target("beta", "b"));
        repository.save(target("Alpha", "a"));
        repository.save(target("charlie", "c"));

        assertThat(repository.findAll())
                .extracting(DatabaseTarget::getName)
                .containsExactly("Alpha", "beta", "charlie");
    }

    @Test
    void rejectsANameAlreadyTakenIgnoringCaseAndSurroundingSpace() {
        repository.save(target("production", "shop"));

        assertThatThrownBy(() -> repository.save(target("PRODUCTION", "other")))
                .isInstanceOf(DuplicateTargetNameException.class)
                .hasMessageContaining("PRODUCTION");
        assertThatThrownBy(() -> repository.save(target("  production ", "other")))
                .isInstanceOf(DuplicateTargetNameException.class);
    }

    /**
     * A taken name is an operator's typo, not a fault: it is caught before the
     * insert, so Hibernate never logs the index violation at WARN.
     */
    @Test
    void aTakenNameIsRefusedWithoutTouchingTheIndex(CapturedOutput output) {
        repository.save(target("production", "shop"));
        int before = output.getAll().length();

        assertThatThrownBy(() -> repository.save(target("Production", "other")))
                .isInstanceOf(DuplicateTargetNameException.class);

        assertThat(output.getAll().substring(before)).doesNotContain("23505");
    }

    @Test
    void refusesToDeleteATargetThatStillHasBackups(CapturedOutput output) {
        DatabaseTarget saved = repository.save(target("production", "shop"));
        backups.save(BackupExecution.started(UUID.randomUUID(), saved.getId(), Instant.parse("2026-09-09T11:00:00Z")));
        int before = output.getAll().length();

        assertThatThrownBy(() -> repository.deleteById(saved.getId()))
                .isInstanceOf(TargetInUseException.class)
                .hasMessageContaining("production");

        assertThat(repository.findById(saved.getId())).isPresent();
        // Refused before the delete, so the foreign key is never hit.
        assertThat(output.getAll().substring(before)).doesNotContain("23503");
    }

    @Test
    void savingAnEditedTargetUpdatesItInPlace() {
        DatabaseTarget saved = repository.save(target("production", "shop"));

        repository.save(saved.edited("production", "10.0.0.5", 3307, "reader", "bmV3"));

        assertThat(repository.findAll()).hasSize(1);
        DatabaseTarget found = repository.findById(saved.getId()).orElseThrow();
        assertThat(found.address()).isEqualTo("10.0.0.5:3307/shop");
        assertThat(found.getUsername()).isEqualTo("reader");
        assertThat(found.getPasswordCiphertext()).isEqualTo("bmV3");
        assertThat(found.getCreatedAt()).isEqualTo(saved.getCreatedAt());
    }

    /** The name check excludes the target itself, so recasing its own name is not a clash. */
    @Test
    void aTargetMayKeepOrRecaseItsOwnName() {
        DatabaseTarget saved = repository.save(target("production", "shop"));

        repository.save(saved.edited("Production", "127.0.0.1", 3306, "backup", null));

        assertThat(repository.findById(saved.getId()).orElseThrow().getName()).isEqualTo("Production");
    }

    @Test
    void renamingOntoAnotherTargetsNameIsRefused() {
        repository.save(target("production", "shop"));
        DatabaseTarget staging = repository.save(target("staging", "shop"));

        assertThatThrownBy(() -> repository.save(staging.edited("PRODUCTION", "127.0.0.1", 3306, "backup", null)))
                .isInstanceOf(DuplicateTargetNameException.class);
        assertThat(repository.findById(staging.getId()).orElseThrow().getName()).isEqualTo("staging");
    }

    @Test
    void anEditThatChangesTheConnectionClearsTheStoredCheck() {
        DatabaseTarget saved = repository.save(target("production", "shop"));
        repository.recordConnectionCheck(saved.getId(), ConnectionCheck.passed(Instant.parse("2026-09-09T11:00:00Z")));
        DatabaseTarget tested = repository.findById(saved.getId()).orElseThrow();

        repository.save(tested.edited("production", "10.0.0.5", 3306, "backup", null));

        assertThat(repository.findById(saved.getId()).orElseThrow().hasBeenTested()).isFalse();
    }

    @Test
    void aNewTargetHasNoConnectionCheck() {
        DatabaseTarget saved = repository.save(target("production", "shop"));

        assertThat(repository.findById(saved.getId()).orElseThrow().hasBeenTested()).isFalse();
    }

    @Test
    void recordsAConnectionCheckAndReadsItBack() {
        DatabaseTarget saved = repository.save(target("production", "shop"));
        Instant checkedAt = Instant.parse("2026-09-09T11:00:00Z");

        repository.recordConnectionCheck(saved.getId(), ConnectionCheck.failed("ERROR 1045: Access denied", checkedAt));

        ConnectionCheck stored = repository.findById(saved.getId()).orElseThrow().getLastConnectionCheck();
        assertThat(stored.successful()).isFalse();
        assertThat(stored.message()).isEqualTo("ERROR 1045: Access denied");
        assertThat(stored.checkedAt()).isEqualTo(checkedAt);
    }

    /**
     * The reason recordConnectionCheck is a targeted update rather than a
     * save() of a reconstructed aggregate: a probe must not be able to touch
     * the credentials.
     */
    @Test
    void recordingACheckLeavesTheStoredPasswordUntouched() {
        DatabaseTarget saved = repository.save(target("production", "shop"));

        repository.recordConnectionCheck(saved.getId(), ConnectionCheck.passed(Instant.parse("2026-09-09T11:00:00Z")));

        DatabaseTarget after = repository.findById(saved.getId()).orElseThrow();
        assertThat(after.getPasswordCiphertext()).isEqualTo("Y2lwaGVydGV4dA==");
        assertThat(after.getName()).isEqualTo("production");
        assertThat(after.getCreatedAt()).isEqualTo(saved.getCreatedAt());
    }

    @Test
    void aLaterCheckReplacesTheEarlierOne() {
        DatabaseTarget saved = repository.save(target("production", "shop"));

        repository.recordConnectionCheck(saved.getId(), ConnectionCheck.failed("first", Instant.parse("2026-09-09T11:00:00Z")));
        repository.recordConnectionCheck(saved.getId(), ConnectionCheck.passed(Instant.parse("2026-09-09T12:00:00Z")));

        ConnectionCheck stored = repository.findById(saved.getId()).orElseThrow().getLastConnectionCheck();
        assertThat(stored.successful()).isTrue();
        assertThat(stored.checkedAt()).isEqualTo(Instant.parse("2026-09-09T12:00:00Z"));
    }

    /** A target deleted in another tab is not worth failing a probe over. */
    @Test
    void recordingACheckForAnUnknownTargetIsNotAnError() {
        repository.recordConnectionCheck(
                UUID.randomUUID(), ConnectionCheck.passed(Instant.parse("2026-09-09T11:00:00Z")));
    }

    /** MySQL errors can be long; the column is 500 characters. */
    @Test
    void storesAnOverlongFailureMessageByTruncatingIt() {
        DatabaseTarget saved = repository.save(target("production", "shop"));

        repository.recordConnectionCheck(
                saved.getId(), ConnectionCheck.failed("x".repeat(900), Instant.parse("2026-09-09T11:00:00Z")));

        assertThat(repository.findById(saved.getId()).orElseThrow()
                .getLastConnectionCheck().message()).hasSize(500);
    }

    @Test
    void deleteRemovesTheTarget() {
        DatabaseTarget saved = repository.save(target("doomed", "shop"));

        repository.deleteById(saved.getId());

        assertThat(repository.findById(saved.getId())).isEmpty();
    }

    @Test
    void deletingAnUnknownIdIsNotAnError() {
        repository.deleteById(UUID.randomUUID());
    }

    /**
     * Every test writes to the same container, so each one clears the table
     * first rather than relying on a rollback: {@code saveAndFlush} plus the
     * duplicate-name test make transaction boundaries a poor thing to lean on.
     */
    @org.junit.jupiter.api.BeforeEach
    void clearTable() {
        // Backups first: a target that still has one cannot be deleted.
        backups.findNewestFirst(1, 1000).items().forEach(backup -> backups.deleteById(backup.getId()));
        List<DatabaseTarget> existing = repository.findAll();
        existing.forEach(target -> repository.deleteById(target.getId()));
    }

    private static DatabaseTarget target(String name, String database) {
        return DatabaseTarget.builder().engine(com.hoangluongtran0309.dbbackup.core.model.DatabaseEngine.MYSQL)
                .id(UUID.randomUUID())
                .name(name)
                .host("127.0.0.1")
                .port(3306)
                .databaseName(database)
                .username("backup")
                .passwordCiphertext("Y2lwaGVydGV4dA==")
                .createdAt(Instant.parse("2026-09-09T10:15:30Z"))
                .build();
    }
}
