package com.hoangluongtran0309.dbbackup.web.security;

import java.util.LinkedHashMap;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.access.AccessDeniedHandlerImpl;
import org.springframework.security.web.access.DelegatingAccessDeniedHandler;
import org.springframework.security.web.csrf.MissingCsrfTokenException;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter.ReferrerPolicy;

import jakarta.servlet.DispatcherType;

/**
 * Every page of the console is behind a sign-in; see ADR-011.
 *
 * <p>This console can overwrite a live schema, so the default is closed: a
 * path not listed here as public needs a signed-in operator, including paths
 * that do not exist — an anonymous visitor learns nothing about which do.
 *
 * <p>CSRF protection is Spring Security's default and stays on. Every form in
 * the templates is written with {@code th:action}, which is what makes the token
 * appear in it; a form written with a plain {@code action} would be refused.
 */
@Configuration
public class SecurityConfig {

    private static final String UNAUTHORIZED = """
            {"ok":false,"data":null,"error":{"code":"UNAUTHORIZED","message":"Valid operator credentials are required","field":null}}""";
    private static final String FORBIDDEN = """
            {"ok":false,"data":null,"error":{"code":"FORBIDDEN","message":"The operator is not allowed to perform this action","field":null}}""";

    /**
     * Everything the console loads is its own: fonts are self-hosted, and no
     * template carries an inline script, style or event handler. Keeping it that
     * way is what lets this be strict — an injected script has nowhere to run.
     */
    static final String CONTENT_SECURITY_POLICY = String.join("; ",
            "default-src 'self'",
            "script-src 'self'",
            "style-src 'self'",
            "img-src 'self'",
            "font-src 'self'",
            "connect-src 'self'",
            "form-action 'self'",
            "frame-ancestors 'none'",
            "base-uri 'none'",
            "object-src 'none'");

    /**
     * The CLI API uses the same single operator account as the console, but is
     * deliberately stateless: it never creates a browser session, never
     * redirects to HTML, and returns the stable API error envelope on refusal.
     */
    @Bean
    @Order(1)
    SecurityFilterChain apiSecurity(HttpSecurity http) throws Exception {
        http
                .securityMatcher("/api/**")
                .authorizeHttpRequests(requests -> requests.anyRequest().authenticated())
                .httpBasic(basic -> basic.authenticationEntryPoint((request, response, error) ->
                        writeJson(response, 401, UNAUTHORIZED)))
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .csrf(csrf -> csrf.disable())
                .requestCache(cache -> cache.disable())
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint((request, response, error) -> {
                            writeJson(response, 401, UNAUTHORIZED);
                        })
                        .accessDeniedHandler((request, response, error) -> {
                            writeJson(response, 403, FORBIDDEN);
                        }));
        return http.build();
    }

    private static void writeJson(jakarta.servlet.http.HttpServletResponse response, int status, String body)
            throws java.io.IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write(body);
    }

    @Bean
    @Order(2)
    SecurityFilterChain consoleSecurity(HttpSecurity http) throws Exception {
        http
                .authorizeHttpRequests(requests -> requests
                        // A failure on a public page is still rendered by
                        // error.html, which is reached by an ERROR dispatch.
                        .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                        .requestMatchers("/login", "/css/**", "/js/**", "/fonts/**", "/svg/**",
                                "/favicon.ico").permitAll()
                        // Status only — details are off in application.yml.
                        // The container healthcheck cannot sign in.
                        .requestMatchers("/actuator/health").permitAll()
                        .anyRequest().authenticated())
                .formLogin(form -> form
                        .loginPage("/login")
                        // Not "always": a deep link followed while signed out,
                        // say to a running backup, is where the operator lands.
                        .defaultSuccessUrl("/databases")
                        .failureUrl("/login?error"))
                .logout(logout -> logout
                        .logoutSuccessUrl("/login?logout")
                        // The session is gone; its id has no business being
                        // presented again.
                        .deleteCookies("JSESSIONID"))
                .exceptionHandling(exceptions -> exceptions
                        .accessDeniedHandler(staleFormHandler()))
                .headers(headers -> headers
                        .contentSecurityPolicy(csp -> csp.policyDirectives(CONTENT_SECURITY_POLICY))
                        .referrerPolicy(referrer -> referrer.policy(ReferrerPolicy.SAME_ORIGIN)));
        return http.build();
    }

    /**
     * What a refused form gets. A token that is <em>missing</em> from the
     * session means the session it lived in has ended — the form was left open
     * past the timeout, or across a restart. The operator is sent to sign in,
     * told that nothing was submitted, rather than shown a bare 403. A token
     * that is present but wrong is a 403, rendered by error.html.
     *
     * <p>Deliberately not {@code invalidSessionUrl}: that fires on every
     * request carrying an unknown session id, and after a restart that is
     * every operator's first page load — each would be told "nothing was
     * submitted" about a GET.
     */
    private static AccessDeniedHandler staleFormHandler() {
        LinkedHashMap<Class<? extends AccessDeniedException>, AccessDeniedHandler> handlers = new LinkedHashMap<>();
        handlers.put(MissingCsrfTokenException.class,
                (request, response, denied) -> response.sendRedirect(request.getContextPath() + "/login?expired"));
        return new DelegatingAccessDeniedHandler(handlers, new AccessDeniedHandlerImpl());
    }

    /**
     * Plain bcrypt rather than the delegating encoder: the configured hash is
     * htpasswd's output, {@code $2y$…}, with no {@code {bcrypt}} prefix.
     */
    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
