# Deployment

One base image and one compose file, plus an operator-built Oracle variant. See
[ADR-009](adr/009-one-image-that-carries-the-mysql-client.md) for the original
packaging decision and [ADR-017](adr/017-route-logical-backups-by-database-engine.md)
for the PostgreSQL client added to it. MongoDB packaging is recorded in
[ADR-018](adr/018-mongodb-archives-and-explicit-authentication-database.md).
SQLite file mounting is recorded in
[ADR-019](adr/019-sqlite-files-below-one-root.md). The optional Oracle pack and
shared Data Pump staging are recorded in
[ADR-020](adr/020-oracle-data-pump-shared-staging-and-optional-client-pack.md).

## What the image contains

A JRE, the application jar, Ubuntu's `mysql-client`, `postgresql-client`,
MongoDB's `mongodb-database-tools`, and `sqlite3`. The MySQL package is Oracle's MySQL,
not MariaDB. That
distinction is load-bearing: MariaDB's `mysqldump` rejects
`--set-gtid-purged`, which this tool always passes, so every MySQL backup would
fail. The PostgreSQL package supplies `psql`, `pg_dump` and `pg_restore`; the
MongoDB package supplies `mongodump` and `mongorestore`. Anyone changing the
base image must check all eight binaries again.

Oracle Instant Client is intentionally not one of them. The base image and
default compose deployment keep `ORACLE_ENABLED=false` and contain no Oracle
binaries.

It runs as an unprivileged user, `dbbackup` (uid 10001), and its healthcheck
asks `/actuator/health`, so it only reports healthy once the application is up
and the metadata store answers. That endpoint needs no sign-in and says UP or
DOWN, nothing more.

## What an operator has to decide

**The encryption key.** `ENCRYPTION_SECRET_KEY` has no default and the
application will not start without it. Losing it makes every stored target
password unrecoverable; there is no rotation mechanism.

**The console password.** There is one account
([ADR-011](adr/011-one-operator-account-from-the-environment.md)):
`OPERATOR_USERNAME`, `admin` unless set, and `OPERATOR_PASSWORD_HASH`, which
has no default. It is a bcrypt hash, never the password:

```bash
docker run --rm -it httpd:2.4-alpine htpasswd -nBC 12 ""
```

prints `:$2y$12$…` — drop the leading colon. In `.env`, put it in single
quotes, `OPERATOR_PASSWORD_HASH='$2y$12$…'`; unquoted, compose reads each `$`
as the start of a variable. Anything that is not a bcrypt hash of cost 10 or
more stops the application at startup. Unlike the encryption key, losing it
costs nothing: generate a new hash and restart.

**Where the backups go.** The compose file uses a named volume, which is enough
to survive the container but not the machine. These artifacts are the reason the
tool exists — getting them somewhere else is outside this tool's job, and
whatever already backs up that host should be pointed at the volume.

**Nothing prunes them.** There is no retention policy
([ADR-008](adr/008-deleting-a-backup-takes-its-history-with-it.md)). The volume
grows until somebody deletes backups through the console — several at a time
from the backup list, or all of a target's with the target
([ADR-015](adr/015-deleting-many-backups-and-a-target-with-them.md)).

**Reaching the databases to be backed up.** A MySQL, PostgreSQL or MongoDB server on the
Docker host is `host.docker.internal` from inside the container; compose maps
that name explicitly because on Linux it does not otherwise exist. A server
elsewhere just needs to be routable from the container.

**Mounting SQLite databases.** Compose bind-mounts
`${SQLITE_HOST_DIR:-./sqlite}` at `/var/lib/dbbackup/sqlite`. Register paths
relative to that root, never container-absolute paths. The application resolves
symlinks and refuses files whose real path escapes the root. The container runs
as uid 10001, which needs read access for Test/backup and write access to the
file and its parent for restore and SQLite journal files.

**Mounting Oracle Data Pump staging.** Oracle writes and reads Data Pump files
on the database server. Create a directory object there and grant only the
schema that owns each target access:

```sql
CREATE DIRECTORY DBBACKUP_PUMP_DIR AS '/srv/dbbackup/oracle-datapump';
GRANT READ, WRITE ON DIRECTORY DBBACKUP_PUMP_DIR TO APP_OWNER;
```

Mount that same bind/NFS/shared storage into the Oracle-pack application image
at `ORACLE_DATAPUMP_ROOT` (the example image uses
`/var/lib/dbbackup/oracle-datapump`). The two path strings need not match, but
they must be views of the same files. The connection test proves both the
Oracle grant and that shared visibility by creating and removing a probe.
Oracle local storage that the application cannot mount, including ASM-only
staging, is not supported.

Build `Dockerfile.oracle.example` only after extracting operator-supplied
Instant Client Basic, SQL*Plus and Tools archives and consolidating their
`instantclient_*` contents under `oracle-client/`. Build the ordinary image as
`dbbackup:base`, then pass it as `BASE_IMAGE`. The variant enables Oracle and
sets all four Oracle paths; override them for a different layout. The base
image remains unchanged.

The Oracle login user is also the schema being backed up and must already
exist. Restore may remap a dump from another source schema into that login,
replaces tables present in the dump, and preserves unrelated objects. It is
not transactional: a failing Data Pump import may leave partial changes.

**Metadata PostgreSQL is not a target.** The `postgres` service in compose holds
the application's target and execution records. It is not offered as a backup
target automatically. Backing it up requires registering a PostgreSQL target
explicitly, with credentials that have the required access.

**Client paths.** The image sets `MYSQL_CLIENT_PATH`, `MYSQLDUMP_PATH`,
`PSQL_PATH`, `PG_DUMP_PATH`, `PG_RESTORE_PATH`, `MONGODUMP_PATH` and
`MONGORESTORE_PATH` and `SQLITE_PATH` to `/usr/bin/...`. `SQLITE_ROOT` is
`/var/lib/dbbackup/sqlite`. A source or custom-image deployment may
override them, but every configured file must be executable or startup fails.
The Oracle variant additionally sets `ORACLE_SQLPLUS_PATH`,
`ORACLE_EXPDP_PATH`, `ORACLE_IMPDP_PATH` and `ORACLE_DATAPUMP_ROOT`.

MongoDB credentials may belong to a database other than the one being backed
up. The registration form therefore asks for an authentication database and
defaults it to `admin`. Passwords are handed to each tool through a temporary
owner-only config file, never through its visible command line.

PostgreSQL's dump tools have major-version compatibility rules: a client that
can read a source is not necessarily able to produce an archive loadable by an
older destination. The first PostgreSQL slice does not install or select among
several client majors. Use `pg_dump --version` and provide `PSQL_PATH`,
`PG_DUMP_PATH` and `PG_RESTORE_PATH` from a client release compatible with both
the source and destination; the integration test proves a matching client and
server major end to end.

## What it does and does not protect

Every page needs a sign-in, and every form carries a CSRF token, so neither a
visitor to port 8080 nor a page open in an operator's browser can start a
restore or delete a backup ([ADR-011](adr/011-one-operator-account-from-the-environment.md)).
What is still up to the deployment:

**No TLS.** The console speaks plain HTTP, so the password and the session
cookie cross the network in clear text unless something encrypts them. Put a
TLS-terminating reverse proxy in front, and then set:

- `SESSION_COOKIE_SECURE=true`, so the browser sends the session cookie only
  over HTTPS;
- `SERVER_FORWARD_HEADERS_STRATEGY=native`, so the application believes the
  proxy's `X-Forwarded-*` headers. Without it the application thinks every
  request arrived over plain HTTP: its redirects — to the sign-in page, and
  after every form — point at `http://`, HSTS is never sent, and the sign-in
  log shows the proxy's address instead of the client's. Only set it when the
  proxy is the only way in; otherwise a client can claim any address.

**No limit on sign-in attempts.** Each guess costs a bcrypt check, which is
slow, but nothing stops a patient attacker. Failed attempts are logged as
`Sign-in failed for '…' from <address>`; rate limiting belongs in the proxy.

**One shared account.** The history says a restore was started, not by whom.

Even signed in, this is a tool that can overwrite production data. Keep it
somewhere only trusted operators can reach — behind a VPN, or bound to a
private interface — and do not expose it to the internet.
