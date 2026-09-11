package com.hoangluongtran0309.dbbackup;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.CookieManager;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import com.hoangluongtran0309.dbbackup.application.backup.RunBackupService;
import com.hoangluongtran0309.dbbackup.application.target.ManageDatabaseTargetService;
import com.hoangluongtran0309.dbbackup.application.target.RegisterTargetCommand;
import com.hoangluongtran0309.dbbackup.core.model.DatabaseTarget;
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
 * what an operator sees when something goes wrong. The same goes for the
 * session cookie's attributes, which only the servlet container writes.
 *
 * <p>Every request goes through a real sign-in, the way a browser's would:
 * each test has its own client, and so its own cookies.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@Import(DbBackupApplicationIT.FailingController.class)
@Testcontainers
class DbBackupApplicationIT {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

    private static final String OPERATOR = "operator";
    private static final String PASSWORD = "correct horse battery staple";
    private static final Pattern CSRF_TOKEN = Pattern.compile("name=\"_csrf\" value=\"([^\"]+)\"");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("dbbackup.operator.username", () -> OPERATOR);
        registry.add("dbbackup.operator.password-hash", () -> new BCryptPasswordEncoder(10).encode(PASSWORD));
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

    @Autowired
    private ManageDatabaseTargetService targets;

    private final HttpClient browser = HttpClient.newBuilder()
            .cookieHandler(new CookieManager())
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    @Test
    void anAnonymousVisitorIsSentToSignIn() throws Exception {
        HttpResponse<String> response = get("/databases");

        assertThat(response.statusCode()).isEqualTo(302);
        assertThat(redirectOf(response)).isEqualTo("/login");
    }

    @Test
    void theRightPasswordOpensTheConsole() throws Exception {
        signIn();

        HttpResponse<String> response = get("/databases");
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("Signed in as <strong>" + OPERATOR + "</strong>");
    }

    /** Only the servlet container writes the cookie, so only here can it be seen. */
    @Test
    void theSessionCookieIsKeptFromScriptsAndOtherSites() throws Exception {
        HttpResponse<String> response = get("/login");

        assertThat(response.headers().allValues("Set-Cookie"))
                .filteredOn(cookie -> cookie.startsWith("JSESSIONID="))
                .singleElement().asString()
                .contains("HttpOnly")
                .contains("SameSite=Lax");
    }

    /**
     * The healthcheck cannot sign in, and it still has to touch the metadata
     * store. What it gets is UP or DOWN — nothing about what is behind it.
     */
    @Test
    void theHealthEndpointIsPublicAndSaysNothingMore() throws Exception {
        // What curl sends. The endpoint speaks JSON only, and answers a
        // browser's Accept: text/html with 406.
        HttpResponse<String> response = get("/actuator/health", "*/*");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).isEqualTo("{\"status\":\"UP\"}");
        assertThat(get("/actuator/env").statusCode()).isNotEqualTo(200);
    }

    /**
     * What a forged cross-site form would send: the operator's own session
     * cookie, but not the token from their page. Refused, and the target is
     * still there.
     */
    @Test
    void aSignedInPostWithoutItsCsrfTokenChangesNothing() throws Exception {
        DatabaseTarget target = targets.register(new RegisterTargetCommand(
                "csrf-" + UUID.randomUUID(), "127.0.0.1", 3306, "shop", "backup", "secret"));
        signIn();
        get("/databases");

        HttpResponse<String> response = post("/databases/" + target.getId() + "/delete", Map.of());

        assertThat(response.statusCode()).isEqualTo(403);
        assertThat(response.body()).contains("That form had gone stale").contains("Nothing was changed");
        assertThat(targets.listAll()).extracting(DatabaseTarget::getId).contains(target.getId());
    }

    @Test
    void signingOutEndsTheSession() throws Exception {
        signIn();
        String token = csrfTokenIn(get("/databases").body());

        HttpResponse<String> response = post("/logout", Map.of("_csrf", token));

        assertThat(response.statusCode()).isEqualTo(302);
        assertThat(redirectOf(response)).isEqualTo("/login?logout");
        assertThat(redirectOf(get("/databases"))).isEqualTo("/login");
    }

    @Test
    void anUnknownAddressGetsTheConsolesNotFoundPage() throws Exception {
        signIn();
        HttpResponse<String> response = get("/no-such-page");

        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(response.body())
                .contains("Page not found")
                .contains("/no-such-page")
                // Inside the console's own shell, with a way back — and a way out.
                .contains("Backup Utility")
                .contains("Go to targets")
                .contains("Sign out")
                .doesNotContain("Whitelabel Error Page");
    }

    @Test
    void aMalformedIdGetsTheBadRequestPage() throws Exception {
        signIn();
        HttpResponse<String> response = get("/executions/not-a-uuid");

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(response.body()).contains("That request could not be read");
    }

    @Test
    void aServerSideFailureGetsAnApologyNotAStackTrace() throws Exception {
        signIn();
        HttpResponse<String> response = get("/test-only/fail");

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

    /** Through the sign-in form, as a browser would, token and all. */
    private void signIn() throws IOException, InterruptedException {
        String token = csrfTokenIn(get("/login").body());

        HttpResponse<String> response = post("/login",
                Map.of("username", OPERATOR, "password", PASSWORD, "_csrf", token));

        assertThat(response.statusCode()).as("sign-in").isEqualTo(302);
        assertThat(redirectOf(response)).as("sign-in").isEqualTo("/databases");
    }

    private static String csrfTokenIn(String html) {
        Matcher matcher = CSRF_TOKEN.matcher(html);
        assertThat(matcher.find()).as("a CSRF token in the page").isTrue();
        return matcher.group(1);
    }

    /**
     * Where a redirect points, without scheme and host. Tomcat, as Spring Boot
     * configures it, makes every redirect absolute from the request's own
     * scheme and host — which is why a TLS proxy in front needs the forwarded
     * headers honoured (see docs/deployment.md).
     */
    private static String redirectOf(HttpResponse<String> response) {
        URI location = URI.create(response.headers().firstValue("Location").orElseThrow());
        return location.getRawQuery() == null ? location.getRawPath()
                : location.getRawPath() + "?" + location.getRawQuery();
    }

    private HttpResponse<String> get(String path) throws IOException, InterruptedException {
        return get(path, "text/html");
    }

    private HttpResponse<String> get(String path, String accept) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Accept", accept)
                .build();
        return browser.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> post(String path, Map<String, String> form)
            throws IOException, InterruptedException {
        String body = form.entrySet().stream()
                .map(field -> URLEncoder.encode(field.getKey(), StandardCharsets.UTF_8) + "="
                        + URLEncoder.encode(field.getValue(), StandardCharsets.UTF_8))
                .collect(Collectors.joining("&"));
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Accept", "text/html")
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        return browser.send(request, HttpResponse.BodyHandlers.ofString());
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
