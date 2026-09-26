package com.hoangluongtran0309.dbbackup.web.security;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.formLogin;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.logout;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.authenticated;
import static org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.unauthenticated;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import com.hoangluongtran0309.dbbackup.web.controller.LoginController;

/**
 * The console's access rules, checked against the real filter chain and a real
 * bcrypt account. The controllers themselves are not loaded: most of what is
 * checked here happens before a request would reach one.
 */
@WebMvcTest(LoginController.class)
@Import({ SecurityConfig.class, OperatorAccountConfig.class })
@TestPropertySource(properties = {
        "dbbackup.operator.username=operator",
        "dbbackup.operator.password-hash=" + SecurityConfigTest.PASSWORD_HASH })
class SecurityConfigTest {

    static final String PASSWORD = "correct horse battery staple";
    /** bcrypt, cost 10, of {@link #PASSWORD}. */
    static final String PASSWORD_HASH = "$2a$10$GBoqx2dLOGXvTrLAxfpxOe8TRLOBQUfAFHKh.DYi1nWt0BRyCALTy";

    @Autowired
    private MockMvc mockMvc;

    @Test
    void anAnonymousVisitorIsSentToSignIn() throws Exception {
        mockMvc.perform(get("/databases"))
                .andExpect(redirectedUrl("/login"));
    }

    @Test
    void anAnonymousApiClientGetsJsonRatherThanAConsoleRedirect() throws Exception {
        mockMvc.perform(get("/api/v1/targets"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith("application/json"))
                .andExpect(jsonPath("$.ok").value(false))
                .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));
    }

    @Test
    void validBasicAuthenticationPassesApiRequestsWithoutACsrfToken() throws Exception {
        // There is no API controller in this focused slice, so 404 proves the
        // request passed both authentication and CSRF filters.
        mockMvc.perform(post("/api/v1/no-such-action").with(httpBasic("operator", PASSWORD)))
                .andExpect(status().isNotFound());
    }

    @Test
    void invalidBasicAuthenticationNeverRedirectsToTheLoginPage() throws Exception {
        mockMvc.perform(get("/api/v1/targets").with(httpBasic("operator", "wrong")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));
    }

    /** Otherwise a 404 against a redirect would say which addresses exist. */
    @Test
    void soIsOneAskingForAnAddressThatDoesNotExist() throws Exception {
        mockMvc.perform(get("/no-such-page"))
                .andExpect(redirectedUrl("/login"));
    }

    @Test
    void theSignInPageIsPublicAndItsFormCarriesACsrfToken() throws Exception {
        mockMvc.perform(get("/login"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("name=\"_csrf\"")))
                // No sidebar: its links lead nowhere until signed in.
                .andExpect(content().string(not(containsString("Primary navigation"))));
    }

    @Test
    void whatTheSignInPageNeedsToLoadIsPublic() throws Exception {
        mockMvc.perform(get("/css/app.css")).andExpect(status().isOk());
        mockMvc.perform(get("/js/theme.js")).andExpect(status().isOk());
        mockMvc.perform(get("/js/app.js")).andExpect(status().isOk());
        mockMvc.perform(get("/svg/brand-mark.svg")).andExpect(status().isOk());
    }

    @Test
    void theRightPasswordSignsIn() throws Exception {
        mockMvc.perform(formLogin().user("operator").password(PASSWORD))
                .andExpect(redirectedUrl("/databases"))
                .andExpect(authenticated().withUsername("operator").withRoles("OPERATOR"));
    }

    @Test
    void aWrongPasswordDoesNot() throws Exception {
        mockMvc.perform(formLogin().user("operator").password("wrong"))
                .andExpect(redirectedUrl("/login?error"))
                .andExpect(unauthenticated());
    }

    /** Same outcome, same words: nothing to tell a guesser the username is right. */
    @Test
    void anUnknownUsernameFailsTheSameWay() throws Exception {
        mockMvc.perform(formLogin().user("root").password(PASSWORD))
                .andExpect(redirectedUrl("/login?error"))
                .andExpect(unauthenticated());
        mockMvc.perform(get("/login").param("error", ""))
                .andExpect(content().string(containsString("Username or password is incorrect")));
    }

    @Test
    @WithMockUser
    void aFormWithTheWrongCsrfTokenIsRefused() throws Exception {
        mockMvc.perform(post("/databases/{id}/delete", UUID.randomUUID()).with(csrf().useInvalidToken()))
                .andExpect(status().isForbidden());
    }

    /**
     * No token at all is what a form left open past the end of its session
     * sends: the token lived in that session. It is refused all the same, but
     * the operator is told why, on the sign-in page, not shown a bare 403.
     */
    @Test
    void aFormSentAfterTheSessionEndedGoesToSignInUnprocessed() throws Exception {
        mockMvc.perform(post("/restores")
                        .param("backup", UUID.randomUUID().toString())
                        .param("_csrf", "token-from-the-dead-session")
                        .with(request -> {
                            request.setRequestedSessionId("dead-session");
                            request.setRequestedSessionIdValid(false);
                            return request;
                        }))
                .andExpect(redirectedUrl("/login?expired"));
        mockMvc.perform(get("/login").param("expired", ""))
                .andExpect(content().string(containsString("nothing was submitted")));
    }

    /**
     * A browser still holding a session id the server no longer knows — after
     * every restart, sessions being in memory — is just signed out. It must
     * not be told "nothing was submitted" about a page it merely opened.
     */
    @Test
    void aStaleSessionCookieOnAPageLoadIsJustSignedOut() throws Exception {
        mockMvc.perform(get("/databases")
                        .with(request -> {
                            request.setRequestedSessionId("from-before-the-restart");
                            request.setRequestedSessionIdValid(false);
                            return request;
                        }))
                .andExpect(redirectedUrl("/login"));
    }

    @Test
    @WithMockUser
    void aFormWithItsTokenGetsPastTheFilter() throws Exception {
        // No controller in this slice, so reaching the dispatcher shows as 404.
        mockMvc.perform(post("/databases/{id}/delete", UUID.randomUUID()).with(csrf()))
                .andExpect(status().isNotFound());
    }

    @Test
    @WithMockUser
    void signingOutEndsTheSessionAndForgetsItsCookie() throws Exception {
        mockMvc.perform(logout())
                .andExpect(redirectedUrl("/login?logout"))
                .andExpect(cookie().maxAge("JSESSIONID", 0))
                .andExpect(unauthenticated());
    }

    /** Or an <img src="/logout"> on any page would sign the operator out. */
    @Test
    @WithMockUser
    void signingOutTakesAPost() throws Exception {
        mockMvc.perform(get("/logout"))
                .andExpect(status().isNotFound())
                .andExpect(authenticated());
    }

    @Test
    @WithMockUser
    void aSignedInOperatorIsNotShownTheSignInFormAgain() throws Exception {
        mockMvc.perform(get("/login"))
                .andExpect(redirectedUrl("/databases"));
    }

    @Test
    void responsesCarryTheSecurityHeaders() throws Exception {
        mockMvc.perform(get("/login"))
                .andExpect(header().string("Content-Security-Policy", SecurityConfig.CONTENT_SECURITY_POLICY))
                .andExpect(header().string("X-Frame-Options", "DENY"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("Referrer-Policy", "same-origin"))
                // Signed-out and back-button: a cached page must not reappear.
                .andExpect(header().string("Cache-Control", containsString("no-store")));
    }
}
