# database-backup-utility

MySQL logical backup and restore, driven from a small web console.

The scope is deliberately narrow: **MySQL, logical dumps, local disk.** There is
no PostgreSQL or MongoDB engine, no physical backup, no scheduler, and no cloud
storage — not "not yet configured", but genuinely absent from the code. Each
capability arrives as one complete vertical slice, code and documentation
together. See [ROADMAP.md](ROADMAP.md) for what exists and what is next.

## What works today

Registering a MySQL target, listing what is registered, testing that it is
reachable, and removing one. The target's password is encrypted with AES-256-GCM
before it is stored.

## Running it

Requires JDK 21, Maven, Docker, and the MySQL client binaries (`mysql`). The
application drives those binaries directly and refuses to start if it cannot
find them — see [ADR-003](docs/adr/003-shelling-out-to-the-mysql-client.md).

```bash
# 1. Metadata store
docker compose up -d postgres

# 2. An encryption key: 32 bytes, Base64. Keep it — passwords encrypted under
#    one key cannot be read back under another.
export ENCRYPTION_SECRET_KEY=$(openssl rand -base64 32)

# 3. Build the modules, then run the web module
mvn -DskipTests install
mvn -pl web spring-boot:run
```

(`spring-boot:run` has to be aimed at `web` alone: pointed at the reactor it
would also try to run the parent pom, which has no main class.)

Then open <http://localhost:8080/databases>.

The application refuses to start without `ENCRYPTION_SECRET_KEY`. That is
deliberate: a default key would mean stored passwords are protected by a secret
that is in the repository.

### Configuration

| Environment variable | Default | Purpose |
| --- | --- | --- |
| `ENCRYPTION_SECRET_KEY` | *none — required* | Base64 of 32 random bytes, the AES-256-GCM key for stored passwords |
| `DB_URL` | `jdbc:postgresql://localhost:5432/dbbackup` | Metadata store |
| `DB_USERNAME` | `dbbackup` | Metadata store user |
| `DB_PASSWORD` | `dbbackup` | Metadata store password |
| `MYSQL_CLIENT_PATH` | `/usr/bin/mysql` | The `mysql` client binary; checked for executability at startup |

## Tests

```bash
mvn test     # unit tests only, no Docker needed
mvn verify   # adds the integration tests, which need Docker
```

`*Test.java` is a plain JUnit test. `*IT.java` runs against real containers via
Testcontainers. H2 is not used anywhere, and no test skips itself when something
it needs is missing.

## Documentation

- [docs/architecture/overview.md](docs/architecture/overview.md) — modules and dependency direction
- [docs/adr/](docs/adr/) — decisions and what they cost
