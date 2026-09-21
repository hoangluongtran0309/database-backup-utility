# database-backup-utility

MySQL, MariaDB, PostgreSQL, MongoDB, SQLite, optional Oracle and optional SQL
Server logical backup and restore, driven from a small web console.

The scope is deliberately narrow: **full logical dumps, same-engine restores,
local disk**. MySQL, MariaDB, PostgreSQL, MongoDB, SQLite, Oracle and SQL Server
are implemented. There is no physical
backup, incremental chain, point-in-time recovery, scheduler or cloud storage.
Each capability arrives as one complete vertical slice, code and documentation
together. See [ROADMAP.md](ROADMAP.md) for what exists and what is next.

## What works today

Registering a MySQL, MariaDB, PostgreSQL, MongoDB or SQLite target — plus
Oracle or SQL Server when its optional client pack is enabled — testing it, running a full
logical backup of it, restoring one of those backups into a target of the
same engine, downloading or deleting its artifact, and reading the history of
all of it. The target's password is encrypted with AES-256-GCM before it is
stored.

A network target's connection details — name, host, port, user, password and, for
MongoDB, its authentication database — can be edited without touching its
backups, so a rotated password is an edit rather
than a new target. Its engine and database are fixed once registered: changing
either means registering another target. See
[ADR-012](docs/adr/012-editing-a-target-keeps-its-schema.md). A SQLite target
instead names an immutable relative file below `SQLITE_ROOT` and has no
credentials.

The console asks you to sign in first. There is one operator account, set from
the environment with a bcrypt hash, and every form carries a CSRF token. See
[ADR-011](docs/adr/011-one-operator-account-from-the-environment.md).

Backups run in the background: starting one redirects to its detail page, which
follows it and updates when it finishes — restores likewise. The backup and
restore lists show fifty at a time, newest first, with links to newer and older
pages. See
[ADR-010](docs/adr/010-the-detail-page-follows-a-running-job.md). The target
list shows each target's newest good backup, and flags a newer attempt that
failed. MySQL and MariaDB artifacts are gzipped SQL named
`<database>_<timestamp>.sql.gz`; each is produced and consumed by that
engine's own client tools. PostgreSQL artifacts are custom-format
archives named `<database>_<timestamp>.dump` and can be inspected with
`pg_restore --list`; MongoDB artifacts are compressed archives named
`<database>_<timestamp>.archive.gz`; SQLite artifacts are gzipped SQL named
`<file>_<timestamp>.sql.gz`; Oracle artifacts are schema-mode Data Pump files
named `<service>_<timestamp>.dmp`; SQL Server artifacts are BACPAC files named
`<database>_<timestamp>.bacpac`.

Each backup records the SHA-256 of its artifact — the same value `sha256sum`
prints for the download. The backup's page can verify the file against it, and
every restore checks it first: an artifact that has changed on disk is not
applied. Backups made before 0.2.0 show "Not recorded". See
[ADR-013](docs/adr/013-a-checksum-for-every-artifact.md).

Restoring overwrites live data, so it asks: the confirmation page names the
database and you type the target's name to proceed. Network-engine restores
apply the dump rather than recreating the database, so objects the backup does
not contain are left alone. SQLite instead replaces the complete destination
after rebuilding and validating a temporary database. SQL Server is the
exception: SqlPackage import accepts only a database that is missing or has no
user-defined objects. This application refuses a non-empty destination and
never drops or clears it. See
[ADR-007](docs/adr/007-restore-applies-a-dump-and-asks-first.md) and
[ADR-022](docs/adr/022-sql-server-bacpac-and-optional-client-pack.md).

A backup can be restored into any registered target of the same engine, not
only the one it was taken from — so a restore drill can go into a scratch
database and leave production alone. Cross-engine conversion is not supported.
MongoDB restores rewrite `<source>.*` namespaces to `<destination>.*`, replace
the collections present in the archive, and leave unrelated collections alone.
The name to type is the destination's. Removing a target removes the records of
restores into it. See
[ADR-014](docs/adr/014-restore-into-any-registered-target.md).

Deleting a backup removes its file, its row and any restore records that refer
to it — the confirmation page counts them first. Several can be ticked on the
backup list and deleted together. A target can be removed with all its backups
by typing its name. Nothing is deleted automatically: there is no retention
policy, so the backup directory grows until somebody prunes it. See
[ADR-008](docs/adr/008-deleting-a-backup-takes-its-history-with-it.md) and
[ADR-015](docs/adr/015-deleting-many-backups-and-a-target-with-them.md).

## Running it

### With Docker — nothing else needed

```bash
echo "ENCRYPTION_SECRET_KEY=$(openssl rand -base64 32)" > .env
docker run --rm -it httpd:2.4-alpine htpasswd -nBC 12 ""   # type a console password twice
```

That prints the password's bcrypt hash as `:$2y$12$…`. Add it to `.env`
without the leading colon and **in single quotes**, or compose will read each
`$` in it as a variable:

```bash
OPERATOR_PASSWORD_HASH='$2y$12$…'
```

```bash
docker compose up --build
```

Then open <http://localhost:8080> and sign in as `admin` with that password
(`OPERATOR_USERNAME` changes the name). The image carries the MySQL, MariaDB,
PostgreSQL, MongoDB and SQLite client tools, so the host needs only Docker.
Oracle and SQL Server are deliberately absent from that base image; see
[Optional Oracle pack](#optional-oracle-pack) and
[Optional SQL Server pack](#optional-sql-server-pack).

Keep that key. Passwords encrypted under one key cannot be read back under
another, and there is no recovery path.

A MySQL, MariaDB, PostgreSQL, MongoDB or SQL Server instance running on the Docker host is
reachable from the container as `host.docker.internal` — use that as the
target's host, not `localhost`.

SQLite files are mounted from `${SQLITE_HOST_DIR:-./sqlite}` into the image.
Register their path relative to that directory and ensure uid 10001 can read
the source and write the destination for restores.

### Optional Oracle pack

Oracle 19c+ support uses server-side Data Pump and is disabled by default. Give
the target schema `READ, WRITE` on a directory object whose filesystem path is
shared with the application container, for example:

```sql
CREATE DIRECTORY DBBACKUP_PUMP_DIR AS '/srv/dbbackup/oracle-datapump';
GRANT READ, WRITE ON DIRECTORY DBBACKUP_PUMP_DIR TO APP_OWNER;
```

Extract operator-supplied Oracle Instant Client Basic, SQL*Plus and Tools and
copy the contents of their common `instantclient_*` directory into
`oracle-client/`. Then build the base image and the example variant:

```bash
docker build -t dbbackup:base .
docker build -f Dockerfile.oracle.example \
  --build-arg BASE_IMAGE=dbbackup:base -t dbbackup:oracle .
```

Run that image with the shared bind/NFS directory mounted at
`/var/lib/dbbackup/oracle-datapump`. Set `ORACLE_ENABLED=true` (already set by
the example image) and register the service name, schema/login user and
directory object. The path referenced by Oracle may differ from the container
mount path, but both must resolve to the same storage. Full deployment details
and restore limitations are in [ADR-020](docs/adr/020-oracle-data-pump-shared-staging-and-optional-client-pack.md).

### Optional SQL Server pack

SQL Server support uses `sqlcmd` for probes and SqlPackage 170.5.96 for BACPAC
export/import. It is disabled by default. Build the base image and the supplied
Linux x86-64 variant:

```bash
docker build -t dbbackup:base .
docker build -f Dockerfile.sqlserver.example \
  --build-arg BASE_IMAGE=dbbackup:base -t dbbackup:sqlserver .
```

The variant supplies .NET 10, SqlPackage and `mssql-tools18`, and enables the
complete SQL Server adapter set. Connections are encrypted and validate the
server certificate and hostname by default. For a deliberately self-signed
development server only, set `SQLSERVER_TRUST_SERVER_CERTIFICATE=true`.
SqlPackage [stages table data during export/import](https://learn.microsoft.com/en-us/sql/tools/sqlpackage/troubleshooting-issues-and-performance-with-sqlpackage?view=sql-server-ver17),
so provision additional free space under `SQLSERVER_TEMP_DIR` comparable to the database being processed.
BACPAC is intended here for databases below roughly 200 GB; use SQL Server's
native physical backup tooling for larger databases.

Backups live in a named volume, `backups`, so they survive the container. If the
application will not start, `docker compose logs app` says why; note that with
`restart: unless-stopped` a bad configuration shows as a restart loop while
`docker compose ps` still reports `Up`.

### From source

Requires JDK 21, Maven, Docker, the MySQL client binaries (`mysql` and
`mysqldump`), the MariaDB client binaries (`mariadb` and `mariadb-dump`), the
PostgreSQL client binaries (`psql`, `pg_dump` and `pg_restore`), MongoDB
Database Tools (`mongodump` and `mongorestore`), and `sqlite3` on the host. If
SQL Server is enabled, SqlPackage 170.5.96 and `sqlcmd` from `mssql-tools18`
are also required. The
application drives those directly and refuses to start if it cannot find them; see
[ADR-003](docs/adr/003-shelling-out-to-the-mysql-client.md) and
[ADR-017](docs/adr/017-route-logical-backups-by-database-engine.md).

```bash
docker compose up -d postgres
export ENCRYPTION_SECRET_KEY=$(openssl rand -base64 32)
export OPERATOR_PASSWORD_HASH='$2y$12$…'   # as above; single quotes here too
mvn -DskipTests install
mvn -pl web spring-boot:run
```

(`spring-boot:run` has to be aimed at `web` alone: pointed at the reactor it
would also try to run the parent pom, which has no main class.)

### Configuration

| Environment variable | Default | Purpose |
| --- | --- | --- |
| `ENCRYPTION_SECRET_KEY` | *none — required* | Base64 of 32 random bytes, the AES-256-GCM key for stored passwords |
| `OPERATOR_PASSWORD_HASH` | *none — required* | bcrypt hash (cost ≥ 10) of the console password; anything else stops startup |
| `OPERATOR_USERNAME` | `admin` | The one account that can sign in to the console |
| `SESSION_TIMEOUT` | `30m` | A signed-in console left idle this long signs out |
| `SESSION_COOKIE_SECURE` | `false` | Send the session cookie over HTTPS only; set `true` behind a TLS proxy, see [deployment](docs/deployment.md) |
| `DB_URL` | `jdbc:postgresql://localhost:5432/dbbackup` | Metadata store |
| `DB_USERNAME` | `dbbackup` | Metadata store user |
| `DB_PASSWORD` | `dbbackup` | Metadata store password |
| `MYSQL_CLIENT_PATH` | `/usr/bin/mysql` | The `mysql` client binary; checked for executability at startup |
| `MYSQLDUMP_PATH` | `/usr/bin/mysqldump` | The `mysqldump` binary; likewise checked at startup |
| `MARIADB_CLIENT_PATH` | `/usr/bin/mariadb` | MariaDB's command-line client for probes and restores |
| `MARIADB_DUMP_PATH` | `/usr/bin/mariadb-dump` | MariaDB's logical dump client |
| `PSQL_PATH` | `/usr/bin/psql` | The `psql` client used to test PostgreSQL targets |
| `PG_DUMP_PATH` | `/usr/bin/pg_dump` | The PostgreSQL custom-format dump client |
| `PG_RESTORE_PATH` | `/usr/bin/pg_restore` | The PostgreSQL custom-archive restore client |
| `MONGODUMP_PATH` | `/usr/bin/mongodump` | The MongoDB connection-test and compressed-archive client |
| `MONGORESTORE_PATH` | `/usr/bin/mongorestore` | The MongoDB archive restore client |
| `SQLITE_PATH` | `/usr/bin/sqlite3` | The SQLite CLI used for checks, dumps and restores |
| `SQLITE_ROOT` | `./sqlite` | Root below which every registered SQLite file must resolve; the image uses `/var/lib/dbbackup/sqlite` |
| `SQLITE_HOST_DIR` | `./sqlite` | Compose-only host directory bind-mounted at `SQLITE_ROOT` |
| `ORACLE_ENABLED` | `false` | Enable the optional Oracle adapter set and expose Oracle in registration |
| `ORACLE_SQLPLUS_PATH` | `/opt/oracle/instantclient/sqlplus` | SQL*Plus used for login and shared-directory probes |
| `ORACLE_EXPDP_PATH` | `/opt/oracle/instantclient/expdp` | Oracle Data Pump export client |
| `ORACLE_IMPDP_PATH` | `/opt/oracle/instantclient/impdp` | Oracle Data Pump import client |
| `ORACLE_DATAPUMP_ROOT` | `./oracle-datapump` | Application view of storage shared with each Oracle directory object |
| `ORACLE_CONNECT_TIMEOUT` | `30s` | Timeout for probes and Data Pump attach/kill control calls |
| `SQLSERVER_ENABLED` | `false` | Enable the optional SQL Server adapter set and expose SQL Server in registration |
| `SQLPACKAGE_PATH` | `/opt/sqlpackage/sqlpackage` | SqlPackage used for BACPAC export and import |
| `SQLCMD_PATH` | `/opt/mssql-tools18/bin/sqlcmd` | `sqlcmd` used for encrypted connection probes |
| `SQLSERVER_CONNECT_TIMEOUT` | `10s` | SQL Server login/connect timeout |
| `SQLSERVER_TRUST_SERVER_CERTIFICATE` | `false` | Keep encryption but skip CA/hostname verification; opt in only for a deliberately untrusted certificate |
| `SQLSERVER_TEMP_DIR` | `./sqlserver-temp` | Per-job SqlPackage staging root; needs free space comparable to the database and is cleaned after each job |
| `BACKUP_DIR` | `./backups` | Where dumps are written; created at startup. Relative, so it follows the working directory — `mvn -pl web spring-boot:run` puts it under `web/`. The image sets it to `/var/lib/dbbackup/backups`. |
| `JOB_CONCURRENCY` | `2` | How many backups and restores may run at once, together |
| `JOB_QUEUE_CAPACITY` | `20` | Beyond this, a job is refused and recorded as failed |
| `BACKUP_TIMEOUT` | `30m` | A dump running longer than this is killed |
| `RESTORE_TIMEOUT` | `60m` | A restore running longer than this is killed |

## Tests

```bash
mvn test     # unit tests only, no Docker needed
mvn verify   # adds the integration tests, which need Docker
```

`*Test.java` is a plain JUnit test. `*IT.java` runs against real containers via
Testcontainers. The SQL Server IT drives the installed `sqlcmd` and SqlPackage
against SQL Server 2022. The Oracle IT drives `sqlplus`/`expdp`/`impdp` through wrappers
inside its Oracle Free container, so no Oracle client is installed on the
runner. H2 is not used anywhere, and no test skips itself when something it
needs is missing.

## Working on it

The repository follows GitFlow: `develop` integrates, `main` holds only tagged
releases, and neither is committed to directly. See
[docs/branching.md](docs/branching.md) for the branch names and the release and
hotfix procedures.

## Documentation

- [docs/architecture/overview.md](docs/architecture/overview.md) — modules and dependency direction
- [docs/branching.md](docs/branching.md) — branching model
- [docs/adr/](docs/adr/) — decisions and what they cost
