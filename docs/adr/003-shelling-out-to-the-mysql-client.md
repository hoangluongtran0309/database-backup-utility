# ADR-003: Probe and drive MySQL through its client binaries, not JDBC

**Status**: Accepted
**Date**: 2026-09
**Related**: [ADR-002](002-aes-256-gcm-for-target-passwords.md)

## Context

Testing whether a target is reachable has two obvious implementations: open a
JDBC connection, or run `mysql --execute="SELECT 1"` as a child process.

JDBC is the more natural thing for a Java application to reach for. It has no
process to manage, no binary to install, no output to parse, and it returns a
typed exception rather than a string.

## Decision

Shell out to the `mysql` client. All of it goes through one `ProcessRunner`,
and the failure message shown to the operator is MySQL's own text, passed
through verbatim.

The application has **no MySQL JDBC driver** on its compile classpath. One
exists at test scope only, because Testcontainers decides a MySQL container is
ready by opening a JDBC connection to it.

## Rationale

The decisive argument is that a backup is `mysqldump`, a child process, reading
credentials from `MYSQL_PWD`. A JDBC probe would test a *different* path: it can
report a healthy target on a host where `mysqldump` is missing, unreadable, or a
version that cannot talk to that server. The probe is only worth having if
passing it predicts that a backup will run, and that means probing the way a
backup runs.

Passing MySQL's own words through matters more than it looks. "Access denied for
user", "Unknown database" and "Can't connect to MySQL server" send an operator
to three different places. A house-written "Connection failed" sends them
nowhere. This is not hypothetical: the integration test for an unreachable
schema had to be corrected once the real server was in front of it, because a
least-privileged backup user gets *"Access denied … to database"* rather than
*"Unknown database"* — MySQL will not confirm whether the schema exists. A
rewritten message would have hidden that distinction.

## Consequences

- The MySQL client binaries are a hard runtime dependency. `MysqlClient` checks
  the configured path at startup and refuses to start if it is not executable,
  so a misconfiguration is a startup failure rather than a puzzling connection
  error hours later.
- CI must install those binaries, and the integration tests must **fail**
  rather than skip when they are absent.
- The literal host `localhost` is rewritten to `127.0.0.1`. The MySQL client
  treats `localhost` as a request for a Unix socket and silently ignores
  `--port`, so a target on `localhost:3307` would otherwise be probed on the
  default socket instead.
- Every subprocess concern — draining both pipes concurrently to avoid the
  classic pipe-buffer deadlock, enforcing a timeout, and passing secrets through
  `ProcessBuilder.environment()` rather than argv or system properties — is
  solved once in `ProcessRunner` instead of at each call site.
