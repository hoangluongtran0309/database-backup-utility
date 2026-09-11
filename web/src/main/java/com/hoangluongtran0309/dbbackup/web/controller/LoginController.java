package com.hoangluongtran0309.dbbackup.web.controller;

import java.security.Principal;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * The sign-in page. Checking the password, and signing out, are Spring
 * Security's filters, not this controller — see {@code SecurityConfig}.
 */
@Controller
public class LoginController {

    @GetMapping("/login")
    String signIn(Principal principal) {
        // A bookmark or the back button can land a signed-in operator here; a
        // second sign-in form is not what they came for.
        return principal != null ? "redirect:/databases" : "login";
    }
}
