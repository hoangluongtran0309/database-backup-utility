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


FROM eclipse-temurin:21-jre

# The application drives these binaries directly and refuses to start without
# them; installing them here is what makes the image self-contained. Ubuntu's
# mysql-client is Oracle's MySQL 8.4, not a MariaDB substitute, which matters:
# MariaDB's mysqldump rejects --set-gtid-purged.
# curl is only here for the HEALTHCHECK below.
RUN apt-get update \
    && DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends \
        mysql-client \
        curl \
    && rm -rf /var/lib/apt/lists/*

RUN useradd --system --create-home --uid 10001 dbbackup \
    && mkdir -p /var/lib/dbbackup/backups \
    && chown -R dbbackup:dbbackup /var/lib/dbbackup

COPY --from=build /src/web/target/web-*.jar /app/app.jar

# Absolute, and outside the working directory: the default ./backups follows
# whatever directory the process happens to start in.
ENV BACKUP_DIR=/var/lib/dbbackup/backups \
    MYSQL_CLIENT_PATH=/usr/bin/mysql \
    MYSQLDUMP_PATH=/usr/bin/mysqldump \
    JAVA_OPTS="-XX:MaxRAMPercentage=75"

# Backups are the point of this tool. Mount a volume here or they die with the
# container.
VOLUME ["/var/lib/dbbackup/backups"]

USER dbbackup
EXPOSE 8080

# Hits a real page, so it goes green only once Flyway has run and the metadata
# store is actually reachable — which is what compose waits on.
HEALTHCHECK --interval=10s --timeout=3s --start-period=40s --retries=5 \
    CMD curl -fsS http://localhost:8080/databases || exit 1

# sh -c to expand JAVA_OPTS, exec so the JVM is PID 1 and receives SIGTERM.
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
