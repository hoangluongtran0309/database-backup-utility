package com.hoangluongtran0309.dbbackup.web.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.PrintWriter;
import java.io.StringWriter;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.security.core.userdetails.UserDetailsService;

/**
 * The account's configuration either works or stops the application from
 * starting, with a message that says what to do — never a sign-in page that
 * nobody can get past.
 */
class OperatorAccountTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(OperatorAccountConfig.class)
            .withPropertyValues("dbbackup.operator.username=admin");

    @Test
    void startsWithABcryptHash() {
        runner.withPropertyValues("dbbackup.operator.password-hash=" + SecurityConfigTest.PASSWORD_HASH)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(UserDetailsService.class).loadUserByUsername("admin")
                            .getAuthorities()).extracting(Object::toString).containsExactly("ROLE_OPERATOR");
                });
    }

    /** htpasswd writes $2y$; Spring writes $2a$. Both must do. */
    @Test
    void acceptsHtpasswdsOutput() {
        runner.withPropertyValues("dbbackup.operator.password-hash="
                        + SecurityConfigTest.PASSWORD_HASH.replaceFirst("^\\$2a", "\\$2y"))
                .run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void refusesToStartWithoutAHash() {
        runner.withPropertyValues("dbbackup.operator.password-hash=")
                .run(context -> assertThat(stackTraceOf(context.getStartupFailure()))
                        .contains("OPERATOR_PASSWORD_HASH is required"));
    }

    /** The mistake worth guarding against, and the value that must not be printed. */
    @Test
    void refusesThePasswordItselfWithoutRepeatingIt() {
        runner.withPropertyValues("dbbackup.operator.password-hash=hunter2-not-a-hash")
                .run(context -> assertThat(stackTraceOf(context.getStartupFailure()))
                        .contains("must be a bcrypt hash")
                        .doesNotContain("hunter2-not-a-hash"));
    }

    /** Cost 4 is fine for a test and a few milliseconds per guess for an attacker. */
    @Test
    void refusesACheapHash() {
        runner.withPropertyValues("dbbackup.operator.password-hash="
                        + SecurityConfigTest.PASSWORD_HASH.replaceFirst("\\$10\\$", "\\$04\\$"))
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void neverPrintsTheHash() {
        assertThat(new OperatorAccount("admin", SecurityConfigTest.PASSWORD_HASH).toString())
                .contains("admin")
                .doesNotContain(SecurityConfigTest.PASSWORD_HASH);
    }

    private static String stackTraceOf(Throwable failure) {
        assertThat(failure).as("startup failure").isNotNull();
        StringWriter out = new StringWriter();
        failure.printStackTrace(new PrintWriter(out));
        return out.toString();
    }
}
