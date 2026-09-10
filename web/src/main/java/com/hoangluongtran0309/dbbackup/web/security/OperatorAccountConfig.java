package com.hoangluongtran0309.dbbackup.web.security;

import java.util.regex.Pattern;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;

/**
 * Who may sign in: one operator, from the environment.
 *
 * <p>Kept apart from {@link SecurityConfig} so that controller slice tests can
 * import the filter chain — the rules they are checked against — without having
 * to supply an account they never sign in with.
 */
@Configuration
@EnableConfigurationProperties(OperatorAccount.class)
class OperatorAccountConfig {

    /** $2a$, $2b$ or $2y$ — htpasswd writes $2y$ — then a cost of 10 to 31. */
    private static final Pattern BCRYPT_HASH =
            Pattern.compile("\\$2[aby]\\$(1\\d|2\\d|3[01])\\$[./A-Za-z0-9]{53}");

    @Bean
    UserDetailsService operatorAccount(OperatorAccount account) {
        // Checked here rather than with @Pattern on the property: a failed
        // binding prints the rejected value in the startup report, and the
        // likeliest wrong value is the password itself, pasted in by mistake.
        if (!BCRYPT_HASH.matcher(account.passwordHash()).matches()) {
            throw new IllegalStateException("OPERATOR_PASSWORD_HASH must be a bcrypt hash with a cost of "
                    + "at least 10, not the password itself — generate one with: "
                    + OperatorAccount.HOW_TO_GENERATE);
        }
        return new InMemoryUserDetailsManager(User.withUsername(account.username())
                .password(account.passwordHash())
                .roles("OPERATOR")
                .build());
    }
}
