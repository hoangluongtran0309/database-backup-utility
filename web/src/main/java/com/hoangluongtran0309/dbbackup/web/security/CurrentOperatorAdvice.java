package com.hoangluongtran0309.dbbackup.web.security;

import java.security.Principal;

import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

/**
 * Puts the signed-in operator's name in every model, for the sidebar's
 * "Signed in as" and its sign-out button. Null when nobody is signed in — the
 * sign-in page itself, or an error rendered for a public path.
 */
@ControllerAdvice
class CurrentOperatorAdvice {

    @ModelAttribute("operatorName")
    String operatorName(Principal principal) {
        return principal == null ? null : principal.getName();
    }
}
