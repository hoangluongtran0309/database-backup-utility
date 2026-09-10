package com.hoangluongtran0309.dbbackup.web.security;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.NotBlank;

/**
 * The one account allowed into the console — see ADR-011.
 *
 * <p>The password is configured as a bcrypt hash, never as itself: the value
 * sits in an environment variable, where {@code docker inspect} and a process
 * listing can read it. {@link OperatorAccountConfig} refuses anything that is
 * not one.
 *
 * <p>No default for the hash, for the same reason the encryption key has none:
 * a default would be a password published in this repository.
 */
@ConfigurationProperties("dbbackup.operator")
@Validated
public record OperatorAccount(

        @NotBlank(message = "OPERATOR_USERNAME must not be blank")
        String username,

        @NotBlank(message = "OPERATOR_PASSWORD_HASH is required — generate one with: "
                + OperatorAccount.HOW_TO_GENERATE)
        String passwordHash) {

    static final String HOW_TO_GENERATE = "docker run --rm -it httpd:2.4-alpine htpasswd -nBC 12 \"\" "
            + "(and drop the leading colon)";

    /**
     * A record prints every component, and this one can end up in a startup
     * failure report. The hash is not the password, but it is what an offline
     * guessing attack needs.
     */
    @Override
    public String toString() {
        return "OperatorAccount[username=" + username + ", passwordHash=<redacted>]";
    }
}
