# ADR-034: The Oracle pack supplies its runtime ABI and probes staging both ways

## Status

Accepted.

**Amends** ADR-020 for the optional image runtime and Data Pump staging
permissions.

## Context

Ubuntu 24.04's `libaio1t64` package installs `libaio.so.1t64`, while Oracle
Instant Client for Linux x64 links against the older `libaio.so.1` soname. The
example Oracle image installed the package but not the compatibility link, so
SQL\*Plus, `expdp` and `impdp` could not start. CI used shell-script client
fixtures and therefore could not exercise dynamic linking.

The shared Data Pump directory also has two writers. Data Pump commonly writes
dump files as `0640` under the Oracle server's user and group, while restore
files are created by the unprivileged application user. A shared mount alone
does not make either file readable by the other process. The original
connection probe tested only Oracle writing a `UTL_FILE` that the application
could read, and failures did not show the file ownership or mode.

## Decision

`Dockerfile.oracle.example` installs `libaio1t64`, provides `libaio.so.1` as a
compatibility link to `libaio.so.1t64`, and runs `ldd` over all three supplied
Oracle clients. An optional image build fails if a client is absent, is not a
loadable ELF executable, or has an unresolved library. CI uses ELF fixtures so
these build checks execute there too; Oracle's licensed files remain outside
the repository and published base image.

The Data Pump share must grant both the Oracle server and application access.
The documented default is one numeric group visible in both containers, the
application added to that supplemental group, and a group-owned setgid
directory with mode `2770`. Equivalent default ACLs are supported. Runtime
code does not require a particular Unix mode because bind mounts, NFS and ACLs
can provide equivalent access.

Connection testing now probes both directions: Oracle writes a file that the
application reads, then the application writes a file that Oracle reads.
Probe cleanup is attempted on every outcome without replacing the primary
failure. Staging failures report the path, numeric owner, group and POSIX mode
when the filesystem exposes them, followed by the shared-group remedy.

## Consequences

- A real Instant Client with a missing shared library is rejected while the
  optional image is built, before an operator can register a target.
- **Test** catches a share that works for backup traffic in only one direction
  before a backup or restore execution is created.
- Data Pump may use stricter file modes than `UTL_FILE`; backup still validates
  the completed dump and reports its actual ownership if the application
  cannot read it.
- Operators must coordinate numeric identities or ACLs across the application
  and Oracle server. The application does not change ownership or widen modes
  on Oracle-managed files.
