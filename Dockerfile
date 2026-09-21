# syntax=docker/dockerfile:1

# Two stages: one that builds the jar, one that runs it. That is the whole
# reason `docker compose up --build` needs nothing on the host but Docker —
# no JDK, no Maven. The build stage contributes nothing to the final image.

FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src

COPY pom.xml .
COPY core core
COPY adapters adapters
COPY application application
COPY web web

# Tests are skipped here on purpose: the integration tests start containers of
# their own, which is not something a container build can do. `mvn verify` on a
# real machine is the gate, and CI runs it.
RUN --mount=type=cache,target=/root/.m2 mvn -B -DskipTests package


FROM eclipse-temurin:21-jre-noble

# The application drives these binaries directly and refuses to start without
# them; installing them here is what makes the image self-contained. Ubuntu's
# Oracle MySQL and MariaDB client packages conflict because MariaDB also ships
# mysql/mysqldump compatibility names. Preserve the real MySQL executables
# before installing MariaDB, then address both client families by explicit
# paths. The version checks below prove that all four preserved executables
# still load after the conflicting MySQL package is removed.
# curl is only here for the HEALTHCHECK below.
RUN apt-get update \
    && DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends \
        ca-certificates \
        curl \
        gnupg \
    && curl -fsSL https://pgp.mongodb.com/server-8.0.asc \
        | gpg --dearmor --yes -o /usr/share/keyrings/mongodb-server-8.0.gpg \
    && echo "deb [arch=amd64,arm64 signed-by=/usr/share/keyrings/mongodb-server-8.0.gpg] https://repo.mongodb.org/apt/ubuntu noble/mongodb-org/8.0 multiverse" \
        > /etc/apt/sources.list.d/mongodb-org-8.0.list \
    && apt-get update \
    && DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends \
        mongodb-database-tools \
        mysql-client \
        postgresql-client \
        sqlite3 \
    && mkdir -p /opt/mysql/bin \
    && cp /usr/bin/mysql /opt/mysql/bin/mysql \
    && cp /usr/bin/mysqldump /opt/mysql/bin/mysqldump \
    && DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends \
        mariadb-client \
    && /opt/mysql/bin/mysql --version \
    && /opt/mysql/bin/mysqldump --version \
    && /usr/bin/mariadb --version \
    && /usr/bin/mariadb-dump --version \
    && rm -rf /var/lib/apt/lists/*

RUN useradd --system --create-home --uid 10001 dbbackup \
    && mkdir -p /var/lib/dbbackup/backups /var/lib/dbbackup/sqlite \
    && chown -R dbbackup:dbbackup /var/lib/dbbackup

COPY --from=build /src/web/target/web-*.jar /app/app.jar

# Absolute, and outside the working directory: the default ./backups follows
# whatever directory the process happens to start in.
ENV BACKUP_DIR=/var/lib/dbbackup/backups \
    MYSQL_CLIENT_PATH=/opt/mysql/bin/mysql \
    MYSQLDUMP_PATH=/opt/mysql/bin/mysqldump \
    MARIADB_CLIENT_PATH=/usr/bin/mariadb \
    MARIADB_DUMP_PATH=/usr/bin/mariadb-dump \
    PSQL_PATH=/usr/bin/psql \
    PG_DUMP_PATH=/usr/bin/pg_dump \
    PG_RESTORE_PATH=/usr/bin/pg_restore \
    MONGODUMP_PATH=/usr/bin/mongodump \
    MONGORESTORE_PATH=/usr/bin/mongorestore \
    SQLITE_PATH=/usr/bin/sqlite3 \
    SQLITE_ROOT=/var/lib/dbbackup/sqlite \
    JAVA_OPTS="-XX:MaxRAMPercentage=75"

# Backups are the point of this tool. Mount a volume here or they die with the
# container.
VOLUME ["/var/lib/dbbackup/backups", "/var/lib/dbbackup/sqlite"]

USER dbbackup
EXPOSE 8080

# Goes green only once the application is up and its metadata store answers —
# which is what compose waits on. The health endpoint rather than a console
# page: every page now needs a sign-in, and the redirect to it would be a 302
# that curl accepts without anything having touched the database.
HEALTHCHECK --interval=10s --timeout=3s --start-period=40s --retries=5 \
    CMD curl -fsS http://localhost:8080/actuator/health || exit 1

# sh -c to expand JAVA_OPTS, exec so the JVM is PID 1 and receives SIGTERM.
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
