# ADR-031: The CLI is a stateless client of the operator HTTP API

## Status

Accepted.

## Context

The application already owns long-running backup, restore and verification
jobs, a bounded executor, startup repair and Quartz trigger reconciliation in
the web process. A CLI that opened metadata PostgreSQL directly would bypass
application service boundaries and could start a second scheduler or worker.
It would also need database and encryption credentials on every operator
machine.

Automation needs the same coverage as the console, predictable JSON, stable
exit behavior and a way to wait for asynchronous work without transferring
ownership of that work to the client process.

## Decision

`web` exposes a versioned `/api/v1` inbound adapter for targets, storage
profiles, notification channels and subscriptions, schedules, retention,
backups, restores and restore verification. Controllers invoke the existing
application services and repositories used by the console. They return the
envelope `{ok,data,error}` and explicit public projections; ciphertext and
write-only secrets are never serialized.

The API uses a higher-priority stateless Spring Security chain with HTTP Basic
and the existing deployment-wide operator account. It has no browser session
or CSRF token, and authentication failures return JSON rather than redirecting
to `/login`. The existing console chain retains form login, sessions and CSRF.

Long-running starts return HTTP 202 with an execution UUID. The CLI polls the
ordinary detail resource until a terminal state unless `--no-wait` is passed.
The server remains alive and owns the job if the CLI disconnects.

`cli` is a fifth Maven module containing only the JDK HTTP client, Jackson and
command routing. It has no dependency on `core`, `application`, `adapters` or
Spring. It supports human-readable structured output and the complete JSON
envelope, deterministic exit codes, password file/stdin/environment input and
resource-secret bindings of the form `--secret FIELD=ENV_VAR`. It refuses to
put known resource secrets on argv. Plain HTTP is accepted only for a loopback
server unless `--allow-http` is explicit.

The runtime image carries the executable CLI jar and a `dbbackup` launcher so
operators can use it through `docker compose exec` without a second image.

## Consequences

- The web deployment remains the single scheduler, repair owner and bounded
  job executor; CLI termination cannot terminate accepted work.
- Operators need HTTP reachability and the same shared credential as the
  console, but do not receive metadata, encryption or Docker credentials.
- HTTP Basic must be protected by TLS outside loopback. The existing one-user
  model still provides no per-command identity, role separation or API token
  revocation.
- `/api/v1` is now an automation contract. Breaking field or semantic changes
  require a new API version rather than silently changing existing clients.
- There is no offline/direct-database CLI mode and no interactive shell.
