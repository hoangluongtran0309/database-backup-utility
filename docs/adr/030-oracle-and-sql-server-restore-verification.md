# ADR-030: Oracle and SQL Server verification use self-contained database images

## Status

Accepted.

## Context

ADR-029 established isolated restore verification for five engines and deferred
Oracle and SQL Server because their restore clients, image terms and startup
costs differ from the smaller database images. Verification must add those
engines without making either optional application client pack mandatory and
without sending a source target credential to a temporary database.

The Oracle Free image already contains Data Pump. Microsoft's SQL Server image
contains the server and sqlcmd, but not SqlPackage, which is required to import
a BACPAC.

## Decision

Oracle verification runs `gvenzl/oracle-free:23-slim-faststart`. It creates a
randomly protected `DBBVERIFY` schema in `FREEPDB1`, copies the dump into a
private directory and connects as the temporary instance's `SYSTEM` user.
Data Pump always remaps the source schema to `DBBVERIFY`. The ordinary restore
and verification adapters share one Data Pump parameter builder. After import,
verification reads every table and rejects any non-generated user object that
remains `INVALID`. An empty schema is valid.

SQL Server verification uses an operator-built image tagged
`dbbackup-verification-sqlserver:2022`. The supplied
`Dockerfile.sqlserver-verification.example` layers SqlPackage 170.5.96 and its
.NET runtime onto `mcr.microsoft.com/mssql/server:2022-latest`. One disposable
container therefore owns both server and client; no private network, sidecar or
published port is needed. A random SA password reaches sqlcmd through
`SQLCMDPASSWORD` and SqlPackage through an owner-only response file. Ordinary
restore and verification share one SqlPackage import builder. Verification
runs `DBCC CHECKDB` and reads every user table; an empty database is valid.

Both adapters are registered independently of `ORACLE_ENABLED` and
`SQLSERVER_ENABLED`; those switches continue to control only ordinary target
backup, restore and connection-test clients. The global verification switch
and Docker socket remain mandatory. SQL Server availability also requires its
custom image to exist locally, so a manual request fails before history is
created when the operator has not built or pulled it.

Oracle and SQL Server have separate startup defaults of ten and five minutes.
Every process remains bounded by the existing restore and cleanup timeouts.
Container names are derived only from the verification UUID, no source
credential is read, and removal must succeed before an attempt can be stored as
successful.

## Consequences

- Restore verification now supports every database engine in the application.
- Oracle Free image terms and the SQL Server EULA remain explicit operator
  responsibilities; SQL Server starts with the Developer edition PID.
- SQL Server verification needs one extra image build, but runtime cleanup and
  startup repair stay single-container operations.
- Both engines have a materially larger image and memory footprint than the
  original verification engines, so deployments must size Docker accordingly.
- Docker socket access remains root-equivalent access to the host.
