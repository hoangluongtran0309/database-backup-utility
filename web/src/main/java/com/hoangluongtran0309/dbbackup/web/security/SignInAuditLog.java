package com.hoangluongtran0309.dbbackup.web.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.security.authentication.event.AbstractAuthenticationFailureEvent;
import org.springframework.security.authentication.event.AuthenticationSuccessEvent;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.authentication.WebAuthenticationDetails;
import org.springframework.stereotype.Component;

/**
 * One log line per sign-in attempt, with where it came from. A run of failures
 * is how someone guessing at the password shows up; nothing else in the
 * console would notice it.
 */
@Component
class SignInAuditLog {

    private static final Logger log = LoggerFactory.getLogger(SignInAuditLog.class);

    @EventListener
    void succeeded(AuthenticationSuccessEvent event) {
        log.info("Sign-in succeeded for '{}' from {}",
                printable(event.getAuthentication().getName()), origin(event.getAuthentication()));
    }

    @EventListener
    void failed(AbstractAuthenticationFailureEvent event) {
        log.warn("Sign-in failed for '{}' from {}: {}",
                printable(event.getAuthentication().getName()), origin(event.getAuthentication()),
                event.getException().getClass().getSimpleName());
    }

    private static String origin(Authentication authentication) {
        return authentication.getDetails() instanceof WebAuthenticationDetails details
                ? details.getRemoteAddress()
                : "unknown";
    }

    /**
     * The username is whatever was typed into the form. Control characters are
     * replaced so that it cannot start a forged line of its own in the log.
     */
    private static String printable(String username) {
        return username == null ? "" : username.replaceAll("\\p{Cntrl}", "?");
    }
}
