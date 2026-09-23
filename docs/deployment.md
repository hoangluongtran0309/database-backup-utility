# Deployment

One base image and one compose file, plus operator-built Oracle and SQL Server variants. See
[ADR-009](adr/009-one-image-that-carries-the-mysql-client.md) for the original
packaging decision and [ADR-017](adr/017-route-logical-backups-by-database-engine.md)
for the PostgreSQL client added to it. MongoDB packaging is recorded in
[ADR-018](adr/018-mongodb-archives-and-explicit-authentication-database.md).
SQLite file mounting is recorded in
[ADR-019](adr/019-sqlite-files-below-one-root.md). The optional Oracle pack and
shared Data Pump staging are recorded in
[ADR-020](adr/020-oracle-data-pump-shared-staging-and-optional-client-pack.md).
MariaDB's co-installed client pair is recorded in
[ADR-021](adr/021-mariadb-uses-its-own-client-tools.md). SQL Server's BACPAC
pack is recorded in
[ADR-022](adr/022-sql-server-bacpac-and-optional-client-pack.md).

## What the image contains

A JRE, the application jar, Oracle's MySQL client, MariaDB's `mariadb-client`,
`postgresql-client`, MongoDB's `mongodb-database-tools`, and `sqlite3`. The
MySQL/MariaDB distinction is load-bearing: MariaDB's dump client rejects the
MySQL-only `--set-gtid-purged`, and the two artifact producers must not be
silently substituted for each other.

Ubuntu's MySQL and MariaDB client packages conflict over the legacy
`mysql`/`mysqldump` names. The image installs MySQL first and preserves its real
executables below `/opt/mysql/bin`, then installs MariaDB and uses its canonical
`mariadb`/`mariadb-dump` names from `/usr/bin`. The build runs `--version` on
all four paths. The PostgreSQL package supplies `psql`, `pg_dump` and
`pg_restore`; the MongoDB package supplies `mongodump` and `mongorestore`.

Oracle Instant Client, SqlPackage and `sqlcmd` are intentionally not among
them. The base image and default compose deployment keep `ORACLE_ENABLED=false`
and `SQLSERVER_ENABLED=false` and contain none of those optional binaries.

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

**Where the backups go.** Local filesystem is the built-in default; compose
stores it in the `backups` named volume. The Storage page can add an
S3-compatible, Google Cloud Storage or Azure Blob Storage profile and a target
can select it for future backups. The bucket or container must already exist.
Use HTTPS in production; HTTP custom endpoints are intended only for
development emulators. S3 static secrets, GCS service-account JSON keys and
Azure storage-account keys are AES-256-GCM encrypted.

Prefer GCS Application Default Credentials (ADC). Depending on the deployment,
ADC can discover a workload identity or a credential file named by
`GOOGLE_APPLICATION_CREDENTIALS`; mount that file read-only when using it in a
container. A stored JSON key is supported for deployments without workload
identity, but key creation, rotation and revocation remain operator duties and
the key should be scoped to the backup bucket. Arbitrary Google credential
configuration JSON is rejected: JSON-key mode accepts only a service account.

The S3 identity needs Put, Get, Head and Delete plus multipart upload and abort
permissions for its bucket/prefix. Configure a bucket lifecycle rule to abort
incomplete multipart uploads left by a process crash. Bucket creation,
encryption policy and object-version cleanup remain operator responsibilities.

The GCS identity needs `storage.objects.create`, `storage.objects.get` and
`storage.objects.delete` for its bucket/prefix. Uploads use resumable sessions;
Google Cloud can retain an unfinished session for up to one week after a
process crash. Bucket soft-delete, Object Versioning, lifecycle and encryption
policy remain operator responsibilities, including cleanup or recovery of old
generations.

Prefer Azure Default Credential. On Azure it can use workload or managed
identity; outside Azure, configure a service principal with `AZURE_TENANT_ID`,
`AZURE_CLIENT_ID` and `AZURE_CLIENT_SECRET`. Set `AZURE_TOKEN_CREDENTIALS=prod`
to keep the production chain from trying developer tools. A user-assigned
managed identity also uses `AZURE_CLIENT_ID`. The identity needs a data-plane
role such as Storage Blob Data Contributor on the selected container. An
encrypted storage-account key is available for on-premises installations and
Azurite, but it grants account-wide authority and should be rotated.

Azure uploads use block blobs. Container creation, soft-delete and version
cleanup, SAS credentials, custom CAs, customer-managed encryption keys and
browser-direct transfer remain outside this slice and are operator concerns.

`STORAGE_STAGING_DIR` needs room for one complete artifact per concurrent
remote-storage job; normal, failed and known interrupted operation directories
are removed. No durable local copy remains after a successful remote upload.

**Retention starts disabled.** Configure it per target in the Retention page to
keep the newest N successful backups. It runs only after a new successful
backup; saving a policy or restarting does not immediately prune existing
files. Backups with restore history, failed attempts and running jobs stay
outside automatic cleanup. Without a policy the volume still grows until an
operator deletes backups through the console. See
[ADR-024](adr/024-retention-keeps-new-unrestored-backups-per-target.md).

**Schedules use the application's clock and one explicit zone each.** Cron
expressions use Quartz syntax, with seconds as the first field. The schedule
row persists in metadata PostgreSQL and its in-memory Quartz trigger is rebuilt
at startup. A fire missed while the application is down is skipped rather than
replayed; after restart the next future fire is shown in the console. This
deployment is intentionally single-instance for scheduling — running several
application replicas would create the same trigger in each replica.

**Reaching the databases to be backed up.** A MySQL, MariaDB, PostgreSQL,
MongoDB or SQL Server instance on the Docker host is `host.docker.internal` from inside the
container; compose maps that name explicitly because on Linux it does not
otherwise exist. A server elsewhere just needs to be routable from the
container.

**MariaDB privileges and restore semantics.** A read-only MariaDB backup login
needs `SELECT`, `SHOW VIEW`, `TRIGGER` and `EVENT` on the source database. On
MariaDB 10.11, dumping routines also requires `SELECT` on `mysql.proc`:

```sql
GRANT SELECT, SHOW VIEW, TRIGGER, EVENT ON shop.* TO 'dbbackup'@'%';
GRANT SELECT ON mysql.proc TO 'dbbackup'@'%';
```

The login used for restore needs the corresponding create, alter, drop and
write privileges on the destination; granting `ALL PRIVILEGES` on only that
database is the simplest setup. A MariaDB artifact is gzip-compressed SQL with
schema, rows, views, routines, triggers and events, but no `CREATE DATABASE` or
`USE`, so it can be applied to a different registered MariaDB database. Objects
represented in the artifact are replaced; destination objects absent from it
remain. Transactional tables have a consistent backup snapshot, while restore
itself is not transactional and an SQL error can leave partial changes. Only
MariaDB-to-MariaDB restore is accepted.

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

**SQL Server BACPAC and temporary space.** Build
`Dockerfile.sqlserver.example` from an already-built base image. The Linux
x86-64 variant pins Microsoft.SqlPackage 170.5.96, adds its .NET 10 runtime and
`mssql-tools18`, enables the adapter pack, and leaves the base image unchanged:

```bash
docker build -t dbbackup:base .
docker build -f Dockerfile.sqlserver.example \
  --build-arg BASE_IMAGE=dbbackup:base -t dbbackup:sqlserver .
```

SQL Server targets use SQL authentication. Backup exports one database to a
`.bacpac`; restore imports it into a database that is absent or contains no
user-defined objects. SqlPackage rejects a non-empty database, and the
application never drops or clears one. The login therefore needs read/export
permissions for backup and either permission to create the destination or the
required DDL/write permissions on a pre-created empty database for restore.
BACPAC does not carry server logins, login passwords or other server-scoped
objects, and database-user passwords are not preserved.

SqlPackage materializes intermediate table data. `SQLSERVER_TEMP_DIR` must be
writable by uid 10001 and should have additional free space comparable to the
database for both export and import. Per-job directories are removed on every
normal, failing and timed-out exit. BACPAC is operationally best suited below
roughly 200 GB; larger databases should use SQL Server native physical backup.

**Metadata PostgreSQL is not a target.** The `postgres` service in compose holds
the application's target and execution records. It is not offered as a backup
target automatically. Backing it up requires registering a PostgreSQL target
explicitly, with credentials that have the required access.

**Client paths.** The image sets `MYSQL_CLIENT_PATH` and `MYSQLDUMP_PATH` below
`/opt/mysql/bin`; `MARIADB_CLIENT_PATH`, `MARIADB_DUMP_PATH`, `PSQL_PATH`,
`PG_DUMP_PATH`, `PG_RESTORE_PATH`, `MONGODUMP_PATH`, `MONGORESTORE_PATH` and
`SQLITE_PATH` point below `/usr/bin`. `SQLITE_ROOT` is
`/var/lib/dbbackup/sqlite`. A source or custom-image deployment may override
them, but every configured file must be executable or startup fails.
The Oracle variant additionally sets `ORACLE_SQLPLUS_PATH`,
`ORACLE_EXPDP_PATH`, `ORACLE_IMPDP_PATH` and `ORACLE_DATAPUMP_ROOT`.
The SQL Server variant sets `SQLPACKAGE_PATH`, `SQLCMD_PATH` and
`SQLSERVER_TEMP_DIR`; enabling that pack with either executable missing makes
startup fail, as does any partial three-adapter configuration.

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

MariaDB likewise uses one configured client pair rather than a version matrix.
The base image carries Ubuntu Noble's MariaDB 10.11 client and the integration
test proves that pair against MariaDB 10.11. A custom deployment must provide a
`mariadb` and `mariadb-dump` pair compatible with both source and destination.

SQL Server connections always request encryption. CA and hostname validation
remain enabled unless an operator explicitly sets
`SQLSERVER_TRUST_SERVER_CERTIFICATE=true`, which is intended only for a known
self-signed development/test server. Per-target certificate settings and
Windows, Microsoft Entra or managed-identity authentication are not supported.

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
