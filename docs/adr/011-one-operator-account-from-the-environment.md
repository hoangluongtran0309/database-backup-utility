# ADR-011: One operator account, configured from the environment

**Status**: Accepted
**Date**: 2026-09
**Supersedes**: the "no authentication" scope limit in [deployment.md](../deployment.md)

## Context

Until now the console had no sign-in. Anyone who could reach port 8080 could
read every target, download any artifact, delete backups and — through
`POST /restores` — overwrite a live schema. The deployment guide said so, and
left the decision about users, sessions and roles for later.

Two holes, not one. Without authentication the port is the only boundary. And
without CSRF tokens, the boundary did not even hold for an operator who was
trusted: any page open in their browser could post a form to the console, and
the browser would send it from inside the network. A restore is one form
post away.

The console is used by a handful of operators, and the image is meant to come
up with nothing on the host but Docker.

## Decision

Every page needs a signed-in operator, and there is exactly one account.

- **The account comes from the environment**: `OPERATOR_USERNAME` (default
  `admin`) and `OPERATOR_PASSWORD_HASH`, a bcrypt hash with a cost of at least
  10. There is no default for the hash. Without one, or with anything that is
  not a bcrypt hash, the application refuses to start — the same rule as the
  encryption key.
- **Form sign-in and a server-side session**, Spring Security's defaults: the
  session identifier is regenerated at sign-in, sign-out is a POST, and the
  session cookie is `HttpOnly` and `SameSite=Lax`. It ends after 30 idle
  minutes (`SESSION_TIMEOUT`).
- **CSRF tokens on every form**, also Spring Security's default. Thymeleaf's
  `th:action` puts the token in each form; a POST without it is refused. A form
  sent after its session ended goes to the sign-in page with "nothing was
  submitted" instead of a bare 403.
- **Public**: the sign-in page, the static assets it loads, and
  `/actuator/health`, which reports UP or DOWN and nothing else. Everything
  else — including addresses that do not exist — redirects an anonymous
  visitor to the sign-in page.
- **Headers**: a strict Content-Security-Policy (scripts, styles, fonts and
  images from this origin only), `X-Frame-Options: DENY`,
  `Referrer-Policy: same-origin`, and `Cache-Control: no-store`, so a page does
  not come back from the browser cache after signing out.
- **Every sign-in attempt is logged**, with the address it came from.

## Rationale

**One account from the environment, rather than a users table.** A table
means a migration, a way to create the first user, a page to manage the rest,
and roles to decide between — a slice three times this size, for a console a
handful of people share. The environment is already where this tool's secrets
live, and it keeps `docker compose up` sufficient. What it costs is stated
below, and the table remains open as a later decision.

**Not OIDC.** Delegating sign-in to an identity provider means none of this
tool's code touches a password, but it needs a provider to exist first. That
contradicts "nothing on the host but Docker".

**A hash, never the password.** The value sits in an environment variable,
where `docker inspect` and a process listing can read it. Rejecting anything
that is not a bcrypt hash also catches the likeliest mistake — pasting the
password itself — at startup. That check runs after binding, not as a
constraint on the property, because a failed binding prints the rejected value
into the startup report.

**Spring Security's defaults, not a variation on them.** Session fixation
protection, the BREACH-resistant CSRF token and the default headers are
well-trodden. Every departure from them is a place to be wrong.

**A health endpoint rather than a console page for the healthcheck.** The
healthcheck used to fetch `/databases`, which touched the metadata store. Now
that page is a 302 to the sign-in form, which curl counts as success before
the database has been reached. The actuator's health endpoint still touches
it, and shows no detail to the unauthenticated caller.

## Consequences

- An anonymous request gets the sign-in page, never a console page, and never
  a 404 that would say which addresses exist.
- Starting the application now needs `OPERATOR_PASSWORD_HASH` as well as
  `ENCRYPTION_SECRET_KEY`. Losing the hash costs nothing: generate a new one
  and restart. Nothing is encrypted with it.
- **Everyone shares one account**, so the console cannot say *who* started a
  restore — only that one was started. The sign-in log shows where each
  session came from, and that is all.
- **There is no lockout and no rate limit on sign-in.** bcrypt at cost 12
  makes each guess slow. Throttling belongs to whatever sits in front of the
  console; a lockout on a single shared account would let anyone lock the
  operators out.
- **Still no TLS.** The password crosses the network in clear text unless a
  TLS-terminating proxy is in front. Behind one, set
  `SESSION_COOKIE_SECURE=true`, and `SERVER_FORWARD_HEADERS_STRATEGY=native`
  so that the proxy's `X-Forwarded-*` headers count. Without that, redirects
  are made absolute as `http://`, HSTS is never sent, and the sign-in log
  shows the proxy's address rather than the client's. See
  [deployment.md](../deployment.md).
- A detail page following a running job ([ADR-010](010-the-detail-page-follows-a-running-job.md))
  polls as the signed-in operator, so it keeps the session alive until the job
  finishes. If the session ends anyway — signed out in another tab — the page
  says so and stops polling.
- No template may carry an inline script, style or event handler: the
  Content-Security-Policy blocks them. The theme bootstrap that used to be
  inline is now `static/js/theme.js`.
- Not in this slice: more than one account, roles, per-person audit of
  actions, remember-me, OIDC.
