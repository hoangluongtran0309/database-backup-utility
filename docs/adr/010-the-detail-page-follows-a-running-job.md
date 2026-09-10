# ADR-010: The detail page follows a running job until it finishes

**Status**: Accepted
**Date**: 2026-09
**Supersedes**: the "reloaded by hand" consequence of [ADR-004](004-persist-the-execution-before-running-it.md)

## Context

ADR-004 gave every backup a URL before any work starts, and left the detail page
static: it showed RUNNING until the operator reloaded it. The reason given was
that "a page that reloads itself is a nuisance while you are reading an error
message".

In use, the page said "Reload the page to see where it got to" and the operator
did exactly that, repeatedly, for as long as the dump took. Restores, which
arrived later and are slower, inherited the same page. The one moment the
operator is actually waiting for — the job finishing — was the one the page
could not show.

## Decision

While a backup or restore is RUNNING, its detail page re-fetches its own URL
every two seconds and replaces the part of the page that can change with the
same part of the fresh response. It stops at the first finished state.

- The server renders every state. The script moves markup; it does not build
  badges, format sizes or decide which buttons appear. What a poll shows is
  what a reload would have shown.
- There is no JSON status endpoint. The page is the status endpoint.
- The block is swapped only when its markup differs, so polls that find
  nothing new leave focus and selection alone.
- A background tab does not poll. A failed poll backs off, doubling up to 30
  seconds, and says so on the page; the job itself is unaffected.
- When the job finishes, a status region announces it ("Backup succeeded")
  for screen reader users, who would otherwise not notice the page changing.
- Without JavaScript the page is what it was: static, and it says to reload.

## Rationale

**ADR-004's objection was to reloading while reading, not to updating.** A
page only has an error message to read once the job has failed — which is a
finished state, where polling has already stopped. Nothing is ever replaced
under someone reading it.

**Re-fetching the page rather than a JSON endpoint** keeps one renderer. A
status endpoint plus client-side rendering is two descriptions of the same
state, and they drift: the previous project this console descends from showed
"SUCCESS" next to "Finished: in progress" for exactly that reason. The cost is
a heavier response every two seconds, for one tab, for the length of one job —
which, for a console used by a handful of operators, is nothing.

**Polling rather than server-sent events or WebSockets**: a job changes state
twice in its life. A persistent connection per open tab, and the proxy
configuration it needs, is not worth two state changes.

## Consequences

- An operator who starts a backup and waits sees it finish without touching
  anything, and the Download and Restore buttons appear when they apply.
- Each open detail page of a running job costs one GET every two seconds.
- The page's changeable part is marked in the template (`data-live`,
  `data-live-active`, `data-live-announce`). A new detail page for a
  long-running job opts in the same way.
- The list pages (Targets, Backups, Restores) remain static. They show many
  jobs at once, and a reload is the right way to refresh a list.
