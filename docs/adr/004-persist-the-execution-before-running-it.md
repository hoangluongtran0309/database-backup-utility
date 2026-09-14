# ADR-004: Persist the execution before starting the backup

**Status**: Accepted
**Date**: 2026-09
**Related**: [ADR-005](005-local-filesystem-artifact-storage.md)

## Context

A `mysqldump` of a real schema takes minutes. Running it inside the HTTP request
that asked for it would hold the connection open for that long, hit whatever
proxy or browser timeout sits in front, and make two concurrent backups
impossible — so the work has to move off the request thread.

That leaves the question of ordering: does the controller start the work and
then record it, or record it and then start the work?

## Decision

`RunBackupService.start` writes a RUNNING row, **commits it**, and only then
submits the job to a bounded pool. It returns the execution's id, and the
controller redirects straight to `/executions/{id}`.

`start` is deliberately **not** `@Transactional`.

The pool is a fixed `ThreadPoolExecutor` with a bounded queue and the default
abort policy. Rejection is caught and recorded as a failed execution.

## Rationale

The persisted identifier is what makes the operation observable. Once the row
exists there is a URL to redirect to, something to reload, and — if the process
dies mid-dump — evidence that the backup was attempted, instead of a task that
existed only in memory.

The ordering is not stylistic. Submitting first opens a window in which the
background thread looks for a row that has not been written yet. The same
window is what `@Transactional` would reintroduce permanently: the row would
be invisible to every other connection until the method returned, and the
background thread would be reading through a different connection.

A bounded queue matters because each running backup is a child process
competing for the same disk. An unbounded queue turns a handful of impatient
clicks into a backlog nobody asked for; `CallerRunsPolicy` would be worse still,
running the dump on the HTTP thread and reintroducing exactly the hang this ADR
exists to avoid. Aborting, and writing that refusal onto the row, is the only
option that stays honest with the operator.

## Consequences

- Every accepted backup has a stable URL before any work starts.
- The detail page shows RUNNING and is reloaded by hand. There is no polling and
  no auto-refresh: a page that reloads itself is a nuisance while you are
  reading an error message. *Superseded by
  [ADR-010](010-the-detail-page-follows-a-running-job.md): the page now follows
  a running job, and stops as soon as it finishes.*
- A row still RUNNING at startup belongs to a process that is gone — backups run
  in this process and nowhere else — so `failInterruptedBackups` marks them
  failed when the application starts. Without it the console would show a dead
  backup as in progress forever.
- Backup threads are daemons, so shutdown is not held up by a long dump. The row
  left behind is repaired at the next startup by the rule above.
- `RUNNING` covers queued as well as executing. A separate QUEUED state would
  cost a write and a label for a distinction nobody can act on.
