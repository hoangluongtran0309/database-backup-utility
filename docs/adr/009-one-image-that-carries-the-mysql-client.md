# ADR-009: One image, built in two stages, carrying the MySQL client

**Status**: Accepted
**Date**: 2026-09
**Related**: [ADR-003](003-shelling-out-to-the-mysql-client.md), [ADR-005](005-local-filesystem-artifact-storage.md)

## Context

The application refuses to start without `mysql` and `mysqldump`
([ADR-003](003-shelling-out-to-the-mysql-client.md)), and it needs a PostgreSQL
to keep its own metadata in. Running it therefore means either installing a JDK,
Maven and the MySQL client tools on the host, or shipping an image.

## Decision

One `Dockerfile` and one `docker-compose.yml`. The Dockerfile has **two stages**:
a Maven stage that builds the jar and a JRE stage that runs it.

The runtime stage installs Ubuntu's `mysql-client`, runs as an unprivileged
user, and keeps artifacts in `/var/lib/dbbackup/backups`, which compose backs
with a named volume.

## Rationale

**Why two stages, when the plan for this rewrite said no multi-stage
Dockerfile.** That exclusion was aimed at the predecessor's four-stage, 68 KB
Dockerfile with helper images for tools this project does not have. The
acceptance criterion for packaging was that `docker compose up` works with
nothing installed on the host — and a single-stage image can only satisfy that
if the jar is built beforehand, which means a JDK and Maven on the host. Two
stages is the smaller of the two costs, and the build stage contributes nothing
to the final image. If the jar is going to be built outside anyway, this
collapses to a fifteen-line single stage; the choice is one `FROM` block.

**Why Ubuntu's `mysql-client` and not `default-mysql-client`.** On Debian and
Ubuntu, `default-mysql-client` is MariaDB. MariaDB's `mysqldump` rejects
`--set-gtid-purged`, which this tool always passes, so every backup would fail
with *unknown variable* — the same class of failure that `--connect-timeout`
already caused on real `mysqldump`. Ubuntu 26.04's `mysql-client` is Oracle's
MySQL 8.4, which is what the integration tests run against. Anyone changing the
base image has to check this again.

**Why a named volume rather than a bind mount.** The artifacts are the reason
this tool exists; they must outlive the container. Nothing about them belongs in
the source tree, and a bind mount into the checkout invites exactly the mistake
that `backups/` in `.gitignore` is there to catch.

**Why the healthcheck fetches a page rather than an actuator endpoint.** It
takes no extra dependency, and hitting a real page means the check only goes
green once Flyway has run and the metadata store is genuinely reachable — which
is what compose is waiting on before it calls the service healthy.

## Consequences

- `docker compose up --build` needs only Docker. The build downloads a Maven
  repository the first time; a BuildKit cache mount keeps that out of the image
  and off the second build.
- Tests are skipped inside the image build: the integration tests start
  containers of their own, which a container build cannot do. `mvn verify` on a
  real machine remains the gate.
- The image is around 425 MB, most of it the JRE and the MySQL client. Slimming
  it is not worth the fragility of hand-copying binaries between images.
- A MySQL running on the Docker host is reachable as `host.docker.internal`;
  compose maps that name explicitly, because on Linux it does not otherwise
  exist.
- With `restart: unless-stopped`, a missing `ENCRYPTION_SECRET_KEY` produces a
  restart loop rather than a stopped container, and `docker compose ps` will
  keep reporting `Up`. The reason is plain in `docker compose logs app`, and the
  README says where to look.
