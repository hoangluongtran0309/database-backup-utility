# ADR-021: MariaDB uses its own client tools

## Status

Accepted.

## Context

MariaDB speaks the MySQL protocol but its logical dump format and client
options have diverged. In particular, MariaDB's dump client rejects the
MySQL-specific `--set-gtid-purged` used by this application's MySQL adapter.
Treating MariaDB as MySQL would therefore make the engine label cosmetic and
leave support dependent on whichever compatibility symlink happened to win at
installation time.

Ubuntu's Oracle MySQL and MariaDB client packages also conflict over the old
`mysql` and `mysqldump` executable names. The application image must retain
both real client families without routing either engine through the other.

## Decision

`MARIADB` is a distinct always-enabled engine with the existing network target
shape, port 3306 and MariaDB's 128-character username limit. `V11` adds the
engine value without adding target columns.

The engine owns three adapters:

- `mariadb --execute=SELECT 1` tests the registered database and credentials;
- `mariadb-dump --single-transaction --routines --triggers --events` streams
  one positional database through gzip to a `.sql.gz` artifact;
- `mariadb` receives the validated, gunzipped SQL stream on restore.

Every command forces `--protocol=TCP`, so a literal `localhost` still uses the
registered port. The password is present only in the child process's
`MYSQL_PWD` environment. It never appears in argv or application logs.
Both behaviors are part of MariaDB's documented
[protocol selection](https://mariadb.com/docs/server/clients-and-utilities/mariadb-client/mariadb-command-line-client)
and [client environment variables](https://mariadb.com/docs/server/server-management/install-and-upgrade-mariadb/configuring-mariadb/mariadb-environment-variables).

The database is positional rather than passed with `--databases`, keeping
`CREATE DATABASE` and `USE` out of the artifact so it can be restored into a
different registered MariaDB database. Restore decompresses the complete
artifact once before starting the client, then streams it on the second read;
a corrupt or truncated gzip therefore never partially reaches the target.

The base image installs Oracle's MySQL package first and copies the real
`mysql` and `mysqldump` executables to `/opt/mysql/bin`. It then installs
Ubuntu Noble's MariaDB client and uses `/usr/bin/mariadb` and
`/usr/bin/mariadb-dump`. Image builds and CI execute `--version` on all four
paths. Custom deployments may override each path independently.

## Consequences

- MySQL and MariaDB produce the same artifact suffix but remain different
  engine values; cross-engine restore is rejected before a job is persisted.
- The dump is consistent for transactional tables. Non-transactional tables
  may change while it is read.
- Restore replaces objects represented by the dump and leaves unrelated
  destination objects alone. It is not transactional; an SQL error can leave
  a partially changed destination, and the failed execution records that fact.
- One configured MariaDB client pair serves all MariaDB targets. Choosing a
  client version compatible with source and destination remains an operator
  responsibility.
- Socket targets, custom TLS, multi-node discovery and non-password
  authentication configuration remain outside this engine slice.
