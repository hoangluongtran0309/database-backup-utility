package com.hoangluongtran0309.dbbackup;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.concurrent.Executor;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import com.hoangluongtran0309.dbbackup.application.backup.RunBackupService;
import com.hoangluongtran0309.dbbackup.core.port.MysqlConnectionTestPort;
import com.hoangluongtran0309.dbbackup.core.port.MysqlLogicalBackupPort;
import com.hoangluongtran0309.dbbackup.core.port.StoragePort;

/**
 * Boots the whole application against a real PostgreSQL.
 *
 * <p>Every other test in this module is a {@code @WebMvcTest} slice, and a
 * slice loads neither the configuration classes nor the wiring between them.
 * That gap let a bean cycle between {@code BackupExecutorConfig} and
 * {@code RunBackupService} reach a manual run with all 127 tests green. This
 * class closes it: if the context cannot be built, this fails.
 *
 * <p>It also runs on a real port, because error pages exist only there: MockMvc
 * never forwards a failed request to {@code /error}, so no slice test can see
 * what an operator sees when something goes wrong.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@Import(DbBackupApplicationIT.FailingController.class)
@Testcontainers
class DbBackupApplicationIT {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("dbbackup.encryption.key", () -> "ZGJiYWNrdXAtaW50ZWdyYXRpb24tdGVzdC1rZXktMzI=");
        registry.add("dbbackup.storage.local.root",
                () -> System.getProperty("java.io.tmpdir") + "/dbbackup-context-it");
    }

    @Autowired
    private ApplicationContext context;

    @Test
    void theApplicationContextStarts() {
        assertThat(context).isNotNull();
    }

    /** Each port is satisfied by exactly one adapter, and the executor exists. */
    @Test
    void everyPortIsWiredToAnAdapter() {
        assertThat(context.getBeanNamesForType(MysqlLogicalBackupPort.class)).hasSize(1);
        assertThat(context.getBeanNamesForType(MysqlConnectionTestPort.class)).hasSize(1);
        assertThat(context.getBeanNamesForType(StoragePort.class)).hasSize(1);
        assertThat(context.getBean(RunBackupService.class)).isNotNull();
        assertThat(context.getBean(Executor.class)).isNotNull();
    }

    /** Flyway ran, and the JPA mappings still match the schema it produced. */
    @Test
    void flywayAndHibernateAgreeOnTheSchema() {
        // ddl-auto=validate means reaching this point at all is the assertion;
        // a drifted mapping would have failed the context above.
        assertThat(context.containsBean("flywayInitializer")).isTrue();
    }

    @LocalServerPort
    private int port;

    @Test
    void anUnknownAddressGetsTheConsolesNotFoundPage() throws Exception {
        HttpResponse<String> response = getHtml("/no-such-page");

        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(response.body())
                .contains("Page not found")
                .contains("/no-such-page")
                // Inside the console's own shell, with a way back.
                .contains("Backup Utility")
                .contains("Go to targets")
                .doesNotContain("Whitelabel Error Page");
    }

    @Test
    void aMalformedIdGetsTheBadRequestPage() throws Exception {
        HttpResponse<String> response = getHtml("/executions/not-a-uuid");

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(response.body()).contains("That request could not be read");
    }

    @Test
    void aServerSideFailureGetsAnApologyNotAStackTrace() throws Exception {
        HttpResponse<String> response = getHtml("/test-only/fail");

        assertThat(response.statusCode()).isEqualTo(500);
        assertThat(response.body())
                .contains("Something went wrong")
                .contains("Try again")
                // The exception's message belongs in the log, not on the page.
                .doesNotContain(FailingController.SECRET)
                .doesNotContain("IllegalStateException")
                // The error model's own `error` attribute must not be shown
                // again as a flash banner above the page.
                .doesNotContain("flash-error");
    }

    private HttpResponse<String> getHtml(String path) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Accept", "text/html")
                .build();
        return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
    }

    /**
     * Something that fails the way a lost database connection would: an
     * unchecked exception nothing in the web layer handles. Nested in this
     * test class, so component scanning leaves it out of every other context.
     */
    @Controller
    static class FailingController {

        static final String SECRET = "jdbc:postgresql://internal-host/secret";

        @GetMapping("/test-only/fail")
        String fail() {
            throw new IllegalStateException("Could not connect to " + SECRET);
        }
    }
}
