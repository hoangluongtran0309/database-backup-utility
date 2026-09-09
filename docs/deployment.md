# Deployment

One image, one compose file. See
[ADR-009](adr/009-one-image-that-carries-the-mysql-client.md) for why they are
shaped this way.

## What the image contains

A JRE, the application jar, and Ubuntu's `mysql-client` — Oracle's MySQL 8.4,
not MariaDB. That distinction is load-bearing: MariaDB's `mysqldump` rejects
`--set-gtid-purged`, which this tool always passes, so every backup would fail.
Anyone changing the base image must check it again.

It runs as an unprivileged user, `dbbackup` (uid 10001), and its healthcheck
fetches a real page, so it only reports healthy once Flyway has run and the
metadata store is reachable.

## What an operator has to decide

**The encryption key.** `ENCRYPTION_SECRET_KEY` has no default and the
application will not start without it. Losing it makes every stored target
password unrecoverable; there is no rotation mechanism.

**Where the backups go.** The compose file uses a named volume, which is enough
to survive the container but not the machine. These artifacts are the reason the
tool exists — getting them somewhere else is outside this tool's job, and
whatever already backs up that host should be pointed at the volume.

**Nothing prunes them.** There is no retention policy
([ADR-008](adr/008-deleting-a-backup-takes-its-history-with-it.md)). The volume
grows until somebody deletes backups through the console.

**Reaching the databases to be backed up.** A MySQL on the Docker host is
`host.docker.internal` from inside the container; compose maps that name
explicitly because on Linux it does not otherwise exist. A MySQL elsewhere just
needs to be routable from the container.

## What it does not do

No TLS, no authentication, no authorisation. Anyone who can reach port 8080 can
read every target, start a backup, download an artifact, and overwrite a live
schema. Put it somewhere only trusted operators can reach — behind a VPN, or
bound to a private interface — and do not expose it to the internet.

That is a deliberate scope limit for this round, not an oversight. Adding
authentication would mean deciding about users, sessions and roles, and none of
that has been decided.
