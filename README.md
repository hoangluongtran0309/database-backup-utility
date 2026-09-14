# database-backup-utility

MySQL logical backup and restore, driven from a small web console.

The scope is deliberately narrow: **MySQL, logical dumps, local disk.** There is
no PostgreSQL or MongoDB engine, no physical backup, no scheduler, and no cloud
storage — not "not yet configured", but genuinely absent from the code. Each
capability arrives as one complete vertical slice, code and documentation
together. See [ROADMAP.md](ROADMAP.md) for what exists and what is next.

## What works today

Registering a MySQL target, testing that it is reachable, running a full
logical backup of it, restoring one of those backups, downloading or deleting
its artifact, and reading the history of all of it. The target's
password is encrypted with AES-256-GCM before it is stored.

A target's connection details — name, host, port, user, password — can be
edited without touching its backups, so a rotated MySQL password is an edit
rather than a new target. Its schema is fixed once registered: a different
schema is a different target. See
[ADR-012](docs/adr/012-editing-a-target-keeps-its-schema.md).

The console asks you to sign in first. There is one operator account, set from
the environment with a bcrypt hash, and every form carries a CSRF token. See
[ADR-011](docs/adr/011-one-operator-account-from-the-environment.md).

Backups run in the background: starting one redirects to its detail page, which
follows it and updates when it finishes — restores likewise. The backup and
restore lists show fifty at a time, newest first, with links to newer and older
pages. See
[ADR-010](docs/adr/010-the-detail-page-follows-a-running-job.md). The target
list shows each target's newest good backup, and flags a newer attempt that
failed. Artifacts are gzipped and named `<schema>_<timestamp>.sql.gz`, readable
with `zcat` like any other archive.

Each backup records the SHA-256 of its artifact — the same value `sha256sum`
prints for the download. The backup's page can verify the file against it, and
every restore checks it first: an artifact that has changed on disk is not
applied. Backups made before 0.2.0 show "Not recorded". See
[ADR-013](docs/adr/013-a-checksum-for-every-artifact.md).

Restoring overwrites live data, so it asks: the confirmation page names the
schema and you type the target's name to proceed. It applies the dump rather
than resetting the schema — tables the backup does not contain are left alone.
See [ADR-007](docs/adr/007-restore-applies-a-dump-and-asks-first.md).

A backup can be restored into any registered target, not only the one it was
taken from — so a restore drill can go into a scratch schema and leave
production alone. The name to type is the destination's. Removing a target
removes the records of restores into it. See
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
(`OPERATOR_USERNAME` changes the name). The image carries the MySQL client
tools, so the host needs only Docker.

Keep that key. Passwords encrypted under one key cannot be read back under
another, and there is no recovery path.

A MySQL running on the Docker host is reachable from the container as
`host.docker.internal` — use that as the target's host, not `localhost`.

Backups live in a named volume, `backups`, so they survive the container. If the
application will not start, `docker compose logs app` says why; note that with
`restart: unless-stopped` a bad configuration shows as a restart loop while
`docker compose ps` still reports `Up`.

### From source

Requires JDK 21, Maven, Docker, and the MySQL client binaries (`mysql` and
`mysqldump`) on the host — the application drives those directly and refuses to
start if it cannot find them, see
[ADR-003](docs/adr/003-shelling-out-to-the-mysql-client.md).

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
Testcontainers. H2 is not used anywhere, and no test skips itself when something
it needs is missing.

## Working on it

The repository follows GitFlow: `develop` integrates, `main` holds only tagged
releases, and neither is committed to directly. See
[docs/branching.md](docs/branching.md) for the branch names and the release and
hotfix procedures.

## Documentation

- [docs/architecture/overview.md](docs/architecture/overview.md) — modules and dependency direction
- [docs/branching.md](docs/branching.md) — branching model
- [docs/adr/](docs/adr/) — decisions and what they cost
