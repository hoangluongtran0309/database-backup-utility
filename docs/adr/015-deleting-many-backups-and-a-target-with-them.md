# ADR-015: Deleting many backups at once, and a target with them

**Status**: Accepted
**Date**: 2026-09
**Supersedes**: one consequence of [ADR-008](008-deleting-a-backup-takes-its-history-with-it.md) — "bulk deletion is not part of this round" — and the refusal to remove a target that has backups
**Refines**: [ADR-008](008-deleting-a-backup-takes-its-history-with-it.md), [ADR-014](014-restore-into-any-registered-target.md)

## Context

Backups live on local disk ([ADR-005](005-local-filesystem-artifact-storage.md))
and nothing deletes them by itself — there is no scheduler and no retention
policy, and both are out of scope. The only way to keep the directory from
filling is an operator deleting backups through the console, and until now that
meant one backup, one confirmation page, one POST at a time.

Removing a target was worse. It was refused while any backup of it existed, so
retiring a target with a year of backups meant deleting every one of them by
hand first.

## Decision

**Several backups at once.** The backup list has a box on every row that is
not running. The ticked ones go to a confirmation page that lists them and
totals what goes with them — artifacts on disk, their size, the restore records
that refer to them. A POST from that page deletes them.

**A target with its backups.** Removing a target that has backups goes to a
page that counts what goes — its backups, their files, and every restore record
that mentions it — and asks for the target's **name to be typed**, as a restore
does ([ADR-014](014-restore-into-any-registered-target.md)). A target without
backups keeps the one-click dialog; if a backup appears between the list and the
click, the server refuses and sends the operator to the page.

**Each backup goes exactly as a single deletion does**: restore records, then
file, then row ([ADR-008](008-deleting-a-backup-takes-its-history-with-it.md)).
Then the records of restores into the target, then the target.

**Refused as a whole, before anything is touched**, when one of them is still
running, is being restored, or — for a target — a restore into it is running.
The confirmation page says so in place of its button.

Still nothing automatic. No "keep the newest N", no age limit.

## Rationale

**Why a GET form of checkboxes.** Every page works without the script, and the
Content-Security-Policy allows no inline one ([ADR-011](011-one-operator-account-from-the-environment.md)).
Ticking boxes in a GET form that lands on a confirmation page needs neither,
and keeps the rule that a deletion is shown before it happens.

**Why typing the name only when backups go.** The backups are the valuable
thing; the target row is a host, a port and an encrypted password that can be
registered again in a minute. Asking for a typed name to remove an empty target
would be friction that protects nothing. The service, not the page, enforces
this: a removal that would take backups without the confirmation is refused
whatever the form sent.

**Why refuse the whole batch rather than skip the busy ones.** A partial
deletion the operator did not ask for is harder to reason about than a refusal
that names the reason. The ones that are busy are few and short-lived; waiting
and trying again is cheap.

**Why a running restore blocks deletion.** A restore is reading that artifact,
and will write its outcome against the backup's row — or, for a target, against
a target row — that would be gone. That was also true of a single deletion, which
now checks the same thing.

**Why still one at a time underneath.** A failure partway — a file that will
not delete — leaves the rest visible and deletable again, in the order ADR-008
chose so that nothing is ever stranded on disk with no row pointing at it. One
transaction around all of them could not roll back the files anyway.

**Why the keys stay `RESTRICT`.** As in ADR-008 and ADR-014: the use case
removes history after the operator confirmed it, the schema never does by
itself. A backup started between the confirmation page and the POST still holds
the target's row, so the removal fails on the key instead of taking a backup
nobody saw counted.

## Consequences

- A target can be retired in one step, and a backup directory pruned a page of
  history at a time.
- Selection is one page of the list at a time; there is no "every backup older
  than". Pruning a long history is a few pages of ticking, not one click.
- The target list offers a dialog or a page depending on whether the target has
  backups, because the two removals ask for different things.
- A deletion of many that fails partway — a file that cannot be removed — has
  deleted the ones before the failure. The console's error page says why, as it
  does for a single deletion; the list then shows what is left.
- Deleting a backup, alone or with others, is refused while a restore of it is
  running.
