# ADR-022: SQL Server uses BACPAC and an optional client pack

## Status

Accepted.

## Context

SQL Server logical portability is provided by SqlPackage's BACPAC export and
import. [Import](https://learn.microsoft.com/en-us/sql/tools/sqlpackage/sqlpackage-import?view=sql-server-ver17)
can create a database or populate one with no user-defined objects, but it does
not replace an existing populated database. SqlPackage also stages table data
locally and is substantially larger than the client set in the base application
image.

SQL Server credentials must not appear in process arguments or logs. Target
connections should be encrypted without silently accepting an untrusted or
wrong-host certificate.

## Decision

`SQLSERVER` is an opt-in, all-or-nothing adapter pack, disabled by default. It
uses the existing network target shape, default port 1433, SQL authentication,
and 128-character limits for database name, login and password. Passwords must
not contain CR, LF or NUL. `V12` only widens the engine constraint; it neither
adds a column nor changes existing rows.

`sqlcmd` runs `SELECT 1` with `SQLCMDPASSWORD` in the child environment.
[SqlPackage 170.5.96](https://learn.microsoft.com/en-us/sql/tools/sqlpackage/sqlpackage-download?view=sql-server-ver17)
performs `/Action:Export` with extraction validation and
`/Action:Import`. Both always request encrypted connections. Certificate and
hostname verification are the default; the deployment-wide
`SQLSERVER_TRUST_SERVER_CERTIFICATE=true` escape hatch must be explicitly
chosen for a known untrusted certificate.

Each SqlPackage invocation receives its password through a short-lived UTF-8
response file created with mode `0600`. The password is never placed in argv or
application logs. Each job also receives a unique staging directory below
`SQLSERVER_TEMP_DIR`. Response files, staging directories and partial export
artifacts are removed on success, client failure and timeout.

Import is attempted only with the documented semantics: the destination may be
missing, in which case SqlPackage creates it if the login is authorized, or it
may contain no user-defined objects. The application never drops, clears or
replaces a database. A populated destination produces a failed restore and is
left unchanged.

The base image remains unchanged. `Dockerfile.sqlserver.example` extends it
for Linux x86-64 with the pinned SqlPackage release, a .NET 10 runtime and
`mssql-tools18`, then enables the pack. An operator may instead provide both
executables and paths in a custom deployment. A missing binary or partial
adapter set fails startup.

## Consequences

- SqlPackage needs [additional temporary disk space](https://learn.microsoft.com/en-us/sql/tools/sqlpackage/troubleshooting-issues-and-performance-with-sqlpackage?view=sql-server-ver17)
  comparable to the database during export and import. BACPAC is recommended
  here for databases below roughly 200 GB; larger databases need SQL Server's
  physical backup tooling.
- BACPAC carries database schema and data, not server logins, login passwords
  or other server-scoped objects. Database-user passwords are not preserved.
- The restore login needs permission to create a missing database or to create
  schema and data in an existing empty database.
- A stored SQL Server target remains visible while the pack is disabled, but
  Test, Backup and Restore are refused before an execution is created.
- Windows Authentication, Microsoft Entra/managed identity, per-target TLS
  certificates and destructive replacement restore are outside this slice.
