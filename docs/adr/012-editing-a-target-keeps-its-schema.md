# ADR-012: Editing a target keeps its schema

**Status**: Accepted
**Date**: 2026-09

## Context

A target could be registered and removed, but not changed. When the MySQL
password was rotated, or the server moved, the target stopped working, and the
only way back was to register a new one. Removing the old one first was not an
option: a target with backups cannot be removed
([ADR-005](005-local-filesystem-artifact-storage.md)), so the history would
either stay attached to a dead target or have to be deleted backup by backup
([ADR-008](008-deleting-a-backup-takes-its-history-with-it.md)) — throwing
away every copy to fix a password.

## Decision

A target can be edited: its name, host, port, username and password. **Its
schema cannot.** It is fixed when the target is registered.

The password field starts empty and, left empty, keeps the stored password. A
password is never sent back to the browser, so there is nothing to pre-fill.

Changing the host, port, username or password clears the target's last
connection check. A rename alone keeps it.

## Rationale

**Why the schema is fixed.** Every backup of a target is a dump of one schema,
and the target is what the history hangs off. A target that could be
re-pointed at `shop_v2` would have a history full of `shop_…` dumps described
as its own, and the list page's "last backup" would name a copy of a database
the target no longer points at. Host and port are different: the same schema
moved to a new server is still the same data. A different schema is a
different target, and registering one costs nothing.

**Why an empty password means "unchanged".** The alternative — requiring the
password on every edit — makes a rename depend on finding a credential the
operator may not have to hand, and invites typing a wrong one just to get past
the form. An empty password is not a valid MySQL credential for a backup user
in any case worth supporting.

**Why the connection check is cleared.** "Connected, 3 days ago" describes the
old host or the old password. Keeping it after they change would show a green
badge for a connection nobody has tried. "Never tested" is the honest state,
and the Test button is beside it.

**Why an edit is a whole-row save rather than a targeted update.** The
connection check is written by a narrow `UPDATE` precisely so that a probe
cannot rewrite the credentials. An edit is the one operation that is meant to,
so it goes through `save` like a registration, and the same unique index
decides a name clash.

## Consequences

- Rotating a MySQL password is an edit and a Test, and every backup stays where
  it was.
- The name is checked against the other targets, ignoring case and surrounding
  space, exactly as at registration. A target may recase its own name.
- A backup already running keeps the connection details it started with; the
  edit applies from the next one.
- Nothing records who edited a target or what it was before. There is one
  shared account ([ADR-011](011-one-operator-account-from-the-environment.md)),
  and no audit trail.
