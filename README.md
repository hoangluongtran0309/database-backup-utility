# database-backup-utility

[![CI](https://github.com/hoangluongtran0309/database-backup-utility/actions/workflows/ci.yml/badge.svg)](https://github.com/hoangluongtran0309/database-backup-utility/actions/workflows/ci.yml)
[![Security](https://github.com/hoangluongtran0309/database-backup-utility/actions/workflows/security.yml/badge.svg)](https://github.com/hoangluongtran0309/database-backup-utility/actions/workflows/security.yml)
[![E2E](https://github.com/hoangluongtran0309/database-backup-utility/actions/workflows/e2e.yml/badge.svg)](https://github.com/hoangluongtran0309/database-backup-utility/actions/workflows/e2e.yml)

Logical backup and restore for **MySQL, MariaDB, PostgreSQL, MongoDB and
SQLite**, plus **Oracle** and **SQL Server** through optional client packs.
You drive it from a small web console, a versioned HTTP API or the `dbbackup`
CLI. Artifacts go to local disk, S3-compatible storage, Google Cloud Storage or
Azure Blob Storage.

![Running a backup with automatic restore verification](docs/tour/gifs/run-backup.gif)

The scope is deliberately narrow: **full logical dumps and same-engine
restores**. There are no physical backups, no incremental chains and no
point-in-time recovery. Each capability arrives as one complete vertical slice,
code and documentation together; [ROADMAP.md](ROADMAP.md) shows what exists
and what comes next.

**→ [Feature guide with screenshots](docs/FEATURES.md)**

## Contents

- [Highlights](#highlights)
- [Screenshots](#screenshots)
- [Supported engines](#supported-engines)
- [Quick start with Docker](#quick-start-with-docker)
- [Operator CLI](#operator-cli)
- [Optional Oracle pack](#optional-oracle-pack)
- [Optional SQL Server pack](#optional-sql-server-pack)
- [Restore verification](#restore-verification)
- [Configuration](#configuration)
- [Building from source](#building-from-source)
- [Tests](#tests)
- [Releases and CI](#releases-and-ci)
- [Contributing](#contributing)
- [Documentation](#documentation)

## Highlights

- **Seven engines, each with its own client tools.** `mysqldump`,
  `mariadb-dump`, `pg_dump` 17, `mongodump`, `sqlite3`, Oracle Data Pump and
  SqlPackage. Every artifact stays in the engine's normal format.
- **Restore anywhere of the same engine.** You can restore into the source or
  into a scratch database for a drill. You confirm by typing the destination's
  name.
- **Four destinations.** Local filesystem, S3-compatible, Google Cloud Storage
  and Azure Blob, each with a built-in connection probe and selectable per
  target.
- **Integrity first.** Every artifact gets a SHA-256 that is checked before
  every restore, and can be re-checked on demand.
- **Isolated restore verification.** It restores into a disposable database
  and health-checks it, either on demand or automatically after each backup.
- **Automation.** Quartz cron schedules in any IANA time zone, and per-target
  retention that never prunes backups with restore history.
- **Notifications.** Telegram, Slack, Email and generic Webhook channels,
  subscribed per target and per event.
- **Operator API and CLI.** `/api/v1` and `dbbackup` use the same services as
  the console. Secrets never travel on the command line.
- **Secure by default.** One bcrypt-hashed operator account, CSRF on every
  form, and AES-256-GCM for every stored credential.

## Screenshots

| Targets across engines | Restore with typed confirmation |
| --- | --- |
| ![Targets](docs/tour/images/37-targets-container-cards.jpg) | ![Restore confirmation](docs/tour/images/09-restore-confirm.jpg) |
| **Remote storage profiles** | **Restore verification (SQL Server)** |
| ![Storage profiles](docs/tour/images/38-storage-container-cards.jpg) | ![Verification](docs/tour/images/36-restore-verification-sqlserver.jpg) |

More in the [feature guide](docs/FEATURES.md); all media is in
[docs/tour/](docs/tour/).

## Supported engines

| Engine | Availability | Client tools | Artifact |
| --- | --- | --- | --- |
| MySQL | Base image | `mysql`, `mysqldump` | `<database>_<timestamp>.sql.gz` |
| MariaDB | Base image | `mariadb`, `mariadb-dump` | `<database>_<timestamp>.sql.gz` |
| PostgreSQL (servers up to 17) | Base image | `psql`, `pg_dump`, `pg_restore` 17 | `<database>_<timestamp>.dump` (custom format) |
| MongoDB | Base image | `mongodump`, `mongorestore` | `<database>_<timestamp>.archive.gz` |
| SQLite | Base image | `sqlite3` | `<file>_<timestamp>.sql.gz` |
| Oracle 19c+ | [Optional pack](#optional-oracle-pack) | SQL\*Plus, `expdp`, `impdp` | `<service>_<timestamp>.dmp` (schema-mode Data Pump) |
| SQL Server | [Optional pack](#optional-sql-server-pack) | `sqlcmd`, SqlPackage 170.5.96 | `<database>_<timestamp>.bacpac` |

On the local filesystem every artifact lives in its own
`<target-id>/<execution-id>/` directory, the same shape as a remote object key,
so two backups never share a file
([ADR-033](docs/adr/033-local-artifacts-live-per-target-and-execution.md)).
Restore behaviour differs by engine; see the
[feature guide](docs/FEATURES.md#at-a-glance).

## Quick start with Docker

The host needs only Docker. Create an encryption key and a bcrypt hash of the
console password:

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

Then start the application and its PostgreSQL metadata store:

```bash
docker compose up --build
```

Open <http://localhost:8080> and sign in as `admin` with that password
(`OPERATOR_USERNAME` changes the name).

> **Keep the encryption key.** Credentials encrypted under one key cannot be
> read back under another, and there is no recovery path.

Things to know:

- **Databases on the Docker host.** A MySQL, MariaDB, PostgreSQL, MongoDB or
  SQL Server instance running on the host is reachable from the container as
  `host.docker.internal`. Use that as the target's host, not `localhost`.
- **SQLite files.** They are mounted from `${SQLITE_HOST_DIR:-./sqlite}`.
  Register each path relative to that directory, and make sure uid 10001 can
  read the source and write the destination for restores.
- **Where backups go.** Backups live in the named volume `backups`, so they
  outlive the container.
- **When it won't start.** `docker compose logs app` says why. With
  `restart: unless-stopped`, a bad configuration shows as a restart loop while
  `docker compose ps` still reports `Up`.
- **Oracle and SQL Server.** Neither is in the base image; see the optional
  packs below.

Production concerns (TLS, cookie security, backups of the metadata store) are
covered in [docs/deployment.md](docs/deployment.md).

## Operator CLI

The image contains a `dbbackup` command. By default it talks to the
application API on loopback, so the CLI never needs a metadata database
credential or the Docker socket:

```bash
export DBBACKUP_API_PASSWORD='the same operator password used by the console'
printf '%s\n' "$DBBACKUP_API_PASSWORD" | docker compose exec -T app \
  dbbackup target list --password-stdin
printf '%s\n' "$DBBACKUP_API_PASSWORD" | docker compose exec -T app \
  dbbackup backup list --password-stdin --output json
```

For a source build, package and run the standalone client jar:

```bash
mvn -pl cli package
DBBACKUP_API_PASSWORD='operator password' \
  java -jar cli/target/cli-*.jar target list
```

Commands follow `dbbackup <resource> <action>`:

```bash
dbbackup backup run --target-id 9f... --output json
dbbackup backup test-restore --id 2a... --no-wait
dbbackup restore run --backup-execution-id 2a... --target-id 7b... \
  --confirmation disaster-recovery
dbbackup subscription set --target-id 7b... \
  --subscription 4c...:BACKUP_FAILED,RESTORE_FAILED,VERIFICATION_FAILED
```

Credentials never go on the command line:

- **Operator login.** The CLI reads `DBBACKUP_API_URL`, `DBBACKUP_API_USERNAME`
  and `DBBACKUP_API_PASSWORD`, or `--server`, `--username`, `--password-file`
  and `--password-stdin`.
- **Resource credentials.** Target, storage and notification credentials are
  rejected as ordinary options, because argv is visible to other processes.
  Bind them to an environment variable instead:

  ```bash
  export TARGET_DATABASE_PASSWORD='database secret'
  dbbackup target add --name production --engine MYSQL --host db.internal \
    --port 3306 --database shop --username backup \
    --secret password=TARGET_DATABASE_PASSWORD
  ```

Other behaviour:

- **HTTP vs HTTPS.** Plain HTTP is accepted only for loopback unless
  `--allow-http` is given. Remote deployments should always use HTTPS.
- **Output.** Default text output prints tables for collections and key/value
  sections for details. `--output json` prints the complete, stable envelope
  for automation.
- **Waiting.** Backup, restore and restore-verification commands wait for a
  final result and return exit code `5` on a failed execution. `--no-wait`
  returns as soon as the server accepts the job.

Run `dbbackup help` for the full matrix, and see the
[HTTP API reference](docs/http-api.md) and
[ADR-031](docs/adr/031-cli-over-the-operator-http-api.md).

## Optional Oracle pack

Oracle 19c+ support uses server-side Data Pump and is disabled by default.

**1. Grant a directory object.** Give the target schema `READ, WRITE` on a
directory object whose filesystem path is shared with the application
container:

```sql
CREATE DIRECTORY DBBACKUP_PUMP_DIR AS '/srv/dbbackup/oracle-datapump';
GRANT READ, WRITE ON DIRECTORY DBBACKUP_PUMP_DIR TO APP_OWNER;
```

**2. Build the image.** Extract operator-supplied Oracle Instant Client Basic,
SQL\*Plus and Tools, and copy the contents of their common `instantclient_*`
directory into `oracle-client/`. Then build the base image and the example
variant:

```bash
docker build -t dbbackup:base .
docker build -f Dockerfile.oracle.example \
  --build-arg BASE_IMAGE=dbbackup:base -t dbbackup:oracle .
```

**3. Share the staging directory.**

- Mount the shared bind or NFS directory at
  `/var/lib/dbbackup/oracle-datapump`.
- `ORACLE_ENABLED=true` is already set by the example image.
- Register the service name, the schema/login user and the directory object.
- The path Oracle sees may differ from the container mount path, but both must
  resolve to the same storage.
- Give the directory one numeric group shared by the Oracle server and the
  application containers, and add the application to it with Compose
  `group_add` (or `docker run --group-add`).
- Make the directory group-owned and setgid with mode `2770`; equivalent ACLs
  also work.

**Test** checks access in both directions. Full deployment details and restore
limitations are in
[ADR-020](docs/adr/020-oracle-data-pump-shared-staging-and-optional-client-pack.md)
and [ADR-034](docs/adr/034-oracle-pack-runtime-and-shared-staging-permissions.md).

## Optional SQL Server pack

SQL Server support uses `sqlcmd` for probes and SqlPackage 170.5.96 for BACPAC
export and import. It is disabled by default. Build the base image and the
supplied Linux x86-64 variant:

```bash
docker build -t dbbackup:base .
docker build -f Dockerfile.sqlserver.example \
  --build-arg BASE_IMAGE=dbbackup:base -t dbbackup:sqlserver .
```

- **What it adds.** The variant supplies .NET 10, SqlPackage and
  `mssql-tools18`, and enables the complete SQL Server adapter set.
- **Certificates.** Connections are encrypted and validate the server
  certificate and hostname by default. Only for a deliberately self-signed
  development server, set `SQLSERVER_TRUST_SERVER_CERTIFICATE=true`.
- **Disk space.** SqlPackage
  [stages table data during export and import](https://learn.microsoft.com/en-us/sql/tools/sqlpackage/troubleshooting-issues-and-performance-with-sqlpackage?view=sql-server-ver17),
  so provision free space under `SQLSERVER_TEMP_DIR` comparable to the
  database being processed.
- **Database size.** BACPAC is intended here for databases below roughly
  200 GB; use SQL Server's native physical backups for larger ones.
- **Restore rule.** Restores accept only a destination that is missing or has
  no user-defined objects. A non-empty destination is refused and never
  dropped or cleared
  ([ADR-022](docs/adr/022-sql-server-bacpac-and-optional-client-pack.md)).

## Restore verification

Restore verification is opt-in. When it is on:

- **Test restore** restores a backup into a disposable database of the same
  engine.
- That database is health-checked and then removed.
- Each target can also verify automatically after every successful backup.

Setup:

- **Turn it on.** Set `DBBACKUP_VERIFICATION_ENABLED=true`.
- **Network engines.** Uncomment the socket mount and the `group_add` block in
  `docker-compose.yml`, and set `DOCKER_SOCKET_GID` to the host socket's group
  id. The image already contains the Docker CLI, and verification containers
  publish no database ports. On SELinux hosts, also see
  [deployment](docs/deployment.md).
- **SQLite.** Needs no socket, but uses the same switch.
- **SQL Server.** First build its self-contained verification image:
  `docker build -f Dockerfile.sqlserver-verification.example -t dbbackup-verification-sqlserver:2022 .`

> **Docker-socket access is equivalent to root control of the Docker host.**
> Expose it only to a trusted application deployment.

The Oracle Free image terms and the SQL Server EULA remain the operator's
responsibility. See
[ADR-029](docs/adr/029-isolated-restore-verification.md) and
[ADR-030](docs/adr/030-oracle-and-sql-server-restore-verification.md).

## Configuration

All settings are environment variables.

### Core

| Variable | Default | Purpose |
| --- | --- | --- |
| `ENCRYPTION_SECRET_KEY` | *none, required* | Base64 of 32 random bytes; the AES-256-GCM key for stored credentials |
| `OPERATOR_PASSWORD_HASH` | *none, required* | bcrypt hash (cost ≥ 10) of the console password; anything else stops startup |
| `OPERATOR_USERNAME` | `admin` | The one account that can sign in |
| `SESSION_TIMEOUT` | `30m` | A signed-in console left idle this long signs out |
| `SESSION_COOKIE_SECURE` | `false` | Send the session cookie over HTTPS only; set `true` behind a TLS proxy, see [deployment](docs/deployment.md) |

### Metadata store

| Variable | Default | Purpose |
| --- | --- | --- |
| `DB_URL` | `jdbc:postgresql://localhost:5432/dbbackup` | PostgreSQL metadata store |
| `DB_USERNAME` | `dbbackup` | Metadata store user |
| `DB_PASSWORD` | `dbbackup` | Metadata store password |

### Storage and jobs

| Variable | Default | Purpose |
| --- | --- | --- |
| `BACKUP_DIR` | `./backups` | Local artifacts, one `<target-id>/<execution-id>/` directory per backup; created at startup. Relative paths follow the working directory, so `mvn -pl web spring-boot:run` puts it under `web/`. The image sets `/var/lib/dbbackup/backups`. |
| `STORAGE_STAGING_DIR` | `<BACKUP_DIR>/.staging` | Per-job staging for remote storage; needs room for one artifact per concurrent job and is cleaned after each operation |
| `JOB_CONCURRENCY` | `2` | How many backups and restores may run at once, together |
| `JOB_QUEUE_CAPACITY` | `20` | Beyond this, a job is refused and recorded as failed |
| `BACKUP_TIMEOUT` | `30m` | A dump running longer than this is killed |
| `RESTORE_TIMEOUT` | `60m` | A restore running longer than this is killed |

### Email notifications

| Variable | Default | Purpose |
| --- | --- | --- |
| `DBBACKUP_SMTP_HOST` | empty | Deployment-wide SMTP host; empty disables Email delivery without blocking startup |
| `DBBACKUP_SMTP_PORT` | `587` | SMTP port |
| `DBBACKUP_SMTP_USERNAME` | empty | Optional SMTP username |
| `DBBACKUP_SMTP_PASSWORD` | empty | Optional SMTP password |
| `DBBACKUP_SMTP_FROM` | `dbbackup@localhost` | Sender address for Email channels |
| `DBBACKUP_SMTP_AUTH` | `true` | Enable SMTP authentication |
| `DBBACKUP_SMTP_STARTTLS` | `true` | Upgrade SMTP connections with STARTTLS |

### Restore verification

| Variable | Default | Purpose |
| --- | --- | --- |
| `DBBACKUP_VERIFICATION_ENABLED` | `false` | Enable manual and per-target automatic isolated restore verification |
| `DBBACKUP_VERIFICATION_MYSQL_IMAGE` | `mysql:8.4` | Disposable MySQL image |
| `DBBACKUP_VERIFICATION_MARIADB_IMAGE` | `mariadb:10.11` | Disposable MariaDB image |
| `DBBACKUP_VERIFICATION_POSTGRESQL_IMAGE` | `postgres:17-alpine` | Disposable PostgreSQL image |
| `DBBACKUP_VERIFICATION_MONGODB_IMAGE` | `mongo:8.0` | Disposable MongoDB image |
| `DBBACKUP_VERIFICATION_ORACLE_IMAGE` | `gvenzl/oracle-free:23-slim-faststart` | Disposable Oracle Free image |
| `DBBACKUP_VERIFICATION_SQLSERVER_IMAGE` | `dbbackup-verification-sqlserver:2022` | Operator-built SQL Server image containing SqlPackage |
| `DBBACKUP_VERIFICATION_PULL_TIMEOUT` | `10m` | Maximum image pull duration |
| `DBBACKUP_VERIFICATION_STARTUP_TIMEOUT` | `2m` | Maximum disposable database startup duration |
| `DBBACKUP_VERIFICATION_ORACLE_STARTUP_TIMEOUT` | `10m` | Oracle Free startup timeout |
| `DBBACKUP_VERIFICATION_SQLSERVER_STARTUP_TIMEOUT` | `5m` | SQL Server startup timeout |
| `DBBACKUP_VERIFICATION_CLEANUP_TIMEOUT` | `30s` | Maximum disposable database cleanup duration |

### Engine client paths

Each binary is checked for executability at startup; the application refuses
to start if one is missing.

| Variable | Default | Purpose |
| --- | --- | --- |
| `MYSQL_CLIENT_PATH` | `/usr/bin/mysql` | MySQL client for probes and restores |
| `MYSQLDUMP_PATH` | `/usr/bin/mysqldump` | MySQL dump client |
| `MARIADB_CLIENT_PATH` | `/usr/bin/mariadb` | MariaDB client for probes and restores |
| `MARIADB_DUMP_PATH` | `/usr/bin/mariadb-dump` | MariaDB dump client |
| `PSQL_PATH` | `/usr/bin/psql` | Tests PostgreSQL targets and reads their server version |
| `PG_DUMP_PATH` | `/usr/bin/pg_dump` | Custom-format dump client; **Test** also checks its major version against the server |
| `PG_RESTORE_PATH` | `/usr/bin/pg_restore` | PostgreSQL custom-archive restore client |
| `MONGODUMP_PATH` | `/usr/bin/mongodump` | MongoDB connection test and compressed-archive client |
| `MONGORESTORE_PATH` | `/usr/bin/mongorestore` | MongoDB archive restore client |
| `SQLITE_PATH` | `/usr/bin/sqlite3` | SQLite CLI for checks, dumps and restores |
| `SQLITE_ROOT` | `./sqlite` | Every registered SQLite file must resolve below this; the image uses `/var/lib/dbbackup/sqlite` |
| `SQLITE_HOST_DIR` | `./sqlite` | Compose only: host directory bind-mounted at `SQLITE_ROOT` |

The base image overrides the three PostgreSQL paths with the versioned
`/usr/lib/postgresql/17/bin` binaries and can back up servers up to major 17.
A custom deployment should point all three at one compatible client set.
`PG_DUMP_PATH` must not be older than the source server, and restoring into a
server older than the client is not guaranteed
([ADR-035](docs/adr/035-postgresql-17-client-and-version-preflight.md)).

### Oracle pack

| Variable | Default | Purpose |
| --- | --- | --- |
| `ORACLE_ENABLED` | `false` | Enable the Oracle adapter set and offer Oracle in registration |
| `ORACLE_SQLPLUS_PATH` | `/opt/oracle/instantclient/sqlplus` | SQL\*Plus for login and shared-directory probes |
| `ORACLE_EXPDP_PATH` | `/opt/oracle/instantclient/expdp` | Data Pump export client |
| `ORACLE_IMPDP_PATH` | `/opt/oracle/instantclient/impdp` | Data Pump import client |
| `ORACLE_DATAPUMP_ROOT` | `./oracle-datapump` | Application view of the storage shared with each Oracle directory object |
| `ORACLE_CONNECT_TIMEOUT` | `30s` | Timeout for probes and Data Pump attach/kill control calls |

### SQL Server pack

| Variable | Default | Purpose |
| --- | --- | --- |
| `SQLSERVER_ENABLED` | `false` | Enable the SQL Server adapter set and offer SQL Server in registration |
| `SQLPACKAGE_PATH` | `/opt/sqlpackage/sqlpackage` | SqlPackage for BACPAC export and import |
| `SQLCMD_PATH` | `/opt/mssql-tools18/bin/sqlcmd` | `sqlcmd` for encrypted connection probes |
| `SQLSERVER_CONNECT_TIMEOUT` | `10s` | Login/connect timeout |
| `SQLSERVER_TRUST_SERVER_CERTIFICATE` | `false` | Keep encryption but skip CA/hostname verification; only for a deliberately untrusted certificate |
| `SQLSERVER_TEMP_DIR` | `./sqlserver-temp` | Per-job SqlPackage staging root; needs free space comparable to the database and is cleaned after each job |

## Building from source

**Requirements:**

- JDK 21, Maven and Docker.
- The MySQL client binaries (`mysql`, `mysqldump`).
- The MariaDB client binaries (`mariadb`, `mariadb-dump`).
- The PostgreSQL 17 client binaries (`psql`, `pg_dump`, `pg_restore`).
- MongoDB Database Tools (`mongodump`, `mongorestore`).
- `sqlite3`.
- If SQL Server is enabled: SqlPackage 170.5.96 and `sqlcmd` from
  `mssql-tools18`.

The application drives those tools directly
([ADR-003](docs/adr/003-shelling-out-to-the-mysql-client.md),
[ADR-017](docs/adr/017-route-logical-backups-by-database-engine.md)).

```bash
docker compose up -d postgres
export ENCRYPTION_SECRET_KEY=$(openssl rand -base64 32)
export OPERATOR_PASSWORD_HASH='$2y$12$…'   # as above; single quotes here too
mvn -DskipTests install
mvn -pl web spring-boot:run
```

`spring-boot:run` has to be aimed at `web` alone: pointed at the reactor, it
would also try to run the parent pom, which has no main class.

## Tests

```bash
mvn test     # unit tests only, no Docker needed
mvn verify   # adds the integration tests, which need Docker
```

- **Unit tests.** `*Test.java` is a plain JUnit test.
- **Integration tests.** `*IT.java` runs against real containers through
  Testcontainers.
- **SQL Server IT.** Drives the installed `sqlcmd` and SqlPackage against SQL
  Server 2022 and its self-contained verification image.
- **Oracle IT.** Drives `sqlplus`/`expdp`/`impdp` through wrappers inside its
  Oracle Free source, and verifies the dump in a second isolated Oracle
  container, so no Oracle client is installed on the runner.
- **Storage tests.** Use Adobe S3Mock and fake-gcs-server in isolated forks.
- **No shortcuts.** H2 is not used anywhere, and no test skips itself when
  something it needs is missing.

Pull requests also build the release-shaped image and run a Compose E2E path
through `/api/v1` and the bundled CLI. It covers authentication, a complete
SQLite backup, checksum, download, verification and restore cycle, and
representative failure paths, without mounting the Docker socket.

## Releases and CI

Published releases include:

- the Linux AMD64 base image on GHCR;
- executable web and CLI JARs;
- SHA-256 checksums;
- SPDX SBOMs;
- GitHub artifact attestations.

Every protected-branch change must pass Maven, container/security and deployed
API/CLI checks. See
[ADR-032](docs/adr/032-required-ci-gates-and-attested-releases.md) and the
[security policy](SECURITY.md).

## Contributing

The repository follows GitFlow:

- `develop` integrates;
- `main` holds only tagged releases;
- neither is committed to directly.

Work happens on `feature/<slice>-<summary>` branches merged into `develop`
through pull requests. See [docs/branching.md](docs/branching.md) for branch
names and the release and hotfix procedures, and [ROADMAP.md](ROADMAP.md) for
the slice list.

## Documentation

- [docs/FEATURES.md](docs/FEATURES.md): every feature, with screenshots and animations
- [docs/deployment.md](docs/deployment.md): the image, the compose file and what an operator has to decide
- [docs/http-api.md](docs/http-api.md): `/api/v1` resources, authentication, envelopes and CLI mapping
- [docs/architecture/overview.md](docs/architecture/overview.md): modules and dependency direction
- [docs/adr/](docs/adr/): architecture decisions and what they cost
- [docs/walkthrough/ISSUES.md](docs/walkthrough/ISSUES.md): problems found by the browser walkthroughs
- [docs/branching.md](docs/branching.md): branching model
- [SECURITY.md](SECURITY.md): vulnerability reporting and supply-chain policy
