# ADR-023: Quartz triggers are derived from backup schedules

## Status

Accepted.

## Context

Manual backups leave the operator responsible for remembering when every
target was last copied. A recurring definition must survive application
restarts, expose its next run in the console, use an explicit time zone, and
still produce the same execution history and artifacts as the existing
**Back up now** action.

Quartz can persist its own job and trigger tables through a JDBC JobStore, but
that would create a second mutable source of truth beside an application-level
schedule aggregate. Spring Boot also supports Quartz's in-memory store and
programmatic `JobDetail` and `Trigger` registration. Its standard JDBC schema
initializer is deliberately unsuitable here because the supplied scripts may
drop and recreate Quartz tables at startup.

## Decision

`backup_schedules` is the durable source of truth. Each row has an immutable
id, unique operator-facing name, target id, Quartz cron expression, IANA time
zone, enabled flag and creation/update timestamps. A target with schedules
cannot be removed until those schedules are deleted, so no recurring intent is
discarded as a side effect.

Quartz uses its RAM job store. Automatic startup is disabled. On
`ApplicationReadyEvent`, after interrupted backup and restore rows are
repaired, the application validates every persisted definition, removes stale
runtime jobs, materializes enabled definitions, and starts Quartz. Creating,
editing, pausing or deleting a schedule updates both the row and its derived
runtime trigger; failures are compensated so the row and trigger do not
quietly diverge.

Every trigger carries only the schedule UUID. `QuartzBackupJob` loads the
current row and calls `RunBackupService.start(targetId)`, so scheduled and
manual backups share engine routing, the bounded worker pool, execution
history, checksum generation, artifact naming and failure behavior. Quartz's
single thread only submits work; it never runs a database client itself.

Cron uses Quartz's six-or-seven-field syntax, including seconds, and each
trigger receives the row's explicit `ZoneId`. Misfires use **do nothing**: if
the application is stopped at a fire time, it resumes at the next future fire
instead of filling the bounded queue with stale backups.

## Consequences

- Schedule definitions survive restarts; volatile Quartz state is completely
  reproducible and needs no additional Quartz schema migrations.
- A disabled schedule has no runtime job and shows no next run, but remains
  editable in the console.
- Downtime does not cause catch-up backups. High availability and coordinated
  multi-instance scheduling would require a later decision to adopt a
  clustered JDBC JobStore or another leader-election mechanism.
- This slice creates backups but never removes them. Automatic retention is a
  separate policy and remains the next roadmap item.
