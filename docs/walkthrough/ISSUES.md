# Issues found during the feature walkthrough

Found while running every console feature in a browser on 2026-10-01
against `develop` at `5224b44` (0.20.0-SNAPSHOT). Each issue is assigned to a
slice in [ROADMAP.md](../../ROADMAP.md#fixes-from-the-0190-walkthrough); its
status changes to *Fixed in slice N* in the commit that fixes it. The suggested fixes are
starting points for that review, not decisions.

ISSUE-15 to ISSUE-17 were found on 2026-10-02 by the feature tour behind
[docs/FEATURES.md](../FEATURES.md), run against `develop` at `5282738` with all
seven engines, the three remote storage emulators and restore verification
enabled. They are assigned to the slices under
[Fixes from the 0.20.0 feature tour](../../ROADMAP.md#fixes-from-the-0200-feature-tour).

Severity scale: **High**: data loss, or a documented feature that cannot work.
**Medium**: a feature fails in a common setup, or the diagnosis is misleading.
**Low**: usability, wording or consistency.

| ID | Severity | Area | Summary | Slice |
|---|---|---|---|---|
| [ISSUE-01](#issue-01) | High | Backups, local storage | Two backups in the same second with the same database name overwrite each other's file | 31 |
| [ISSUE-02](#issue-02) | High | Oracle pack | `Dockerfile.oracle.example` lacks `libaio.so.1`; the real Instant Client cannot start | 32 |
| [ISSUE-03](#issue-03) | Medium | PostgreSQL | Bundled `pg_dump` 16 cannot back up PostgreSQL 17, and *Test* still says "Connected" | 33 |
| [ISSUE-04](#issue-04) | Medium | Oracle | Data Pump staging needs a shared group; this is undocumented, and the error hides "permission denied" | 32 |
| [ISSUE-05](#issue-05) | Medium | Oracle | All schemas in one Oracle service share the artifact prefix, which makes ISSUE-01 likelier | 31 |
| [ISSUE-06](#issue-06) | Low | Security, UX | Chrome offers the *database* credentials on the operator sign-in page | 35 |
| [ISSUE-07](#issue-07) | Low | UI layout | Targets and storage tables scroll sideways at 1366 px and hide the action buttons | 35 |
| [ISSUE-08](#issue-08) | Low | UI layout | Page headers with many buttons squeeze and wrap the title | 35 |
| [ISSUE-09](#issue-09) | Low | HTTP API | Omitting `verifyAfterBackup` returns a raw Jackson error | 34 |
| [ISSUE-10](#issue-10) | Low | HTTP API | Validation returns one error per request, not the first one in field order | 34 |
| [ISSUE-11](#issue-11) | Low | Notifications | Webhook `occurredAt` is a decimal epoch number, not ISO-8601 | 34 |
| [ISSUE-12](#issue-12) | Low | Storage UI | Azure profiles say "bucket" where Azure has a "container" | 35 |
| [ISSUE-13](#issue-13) | Low | Schedules UI | The cron hint is fixed text, not a description of the entered expression | 35 |
| [ISSUE-14](#issue-14) | Low | CLI | `--output text` (the default) prints JSON | 34 |
| [ISSUE-15](#issue-15) | High | Oracle pack | `Dockerfile.oracle.example` cannot build with Instant Client 23.26: its `ldd` check runs without `LD_LIBRARY_PATH` | 37 |
| [ISSUE-16](#issue-16) | Low | UI layout | Between 1400 px and about 1560 px the targets and storage tables still scroll sideways and hide most action buttons | 38 |
| [ISSUE-17](#issue-17) | Low | Notifications | Lifecycle webhook payloads send `channel.id` and `channel.name` as `null` | 39 |

---

## ISSUE-01

**Two local backups that start in the same second with the same database name overwrite each other's file**

- **Severity:** High (silent loss of a backup that the UI shows as *Succeeded*)
- **Area:** backups on the local filesystem destination
- **Status:** Fixed in slice 31. Local artifacts now live in `<target-id>/<execution-id>/`, and an existing file is never overwritten. See [ADR-033](../adr/033-local-artifacts-live-per-target-and-execution.md).

**Steps to reproduce** (both seen in this walkthrough)

1. *Different targets:* register `shop-mysql` (`mysql:3306/shop`) and
   `shop-mariadb` (`mariadb:3306/shop`), both on *Local filesystem*. Start both
   backups at once. Here they both started at 03:22:42.
2. *Same target:* press **Back up now** twice quickly (or let a schedule fire
   while a manual backup starts) on one target. Here two POSTs to
   `shop-sqlite-restore` both started at 03:40:26.

**Expected:** each backup writes its own artifact.

**Actual**

- Both executions record the same artifact name, for example
  `shop_20261001_032242.sql.gz`. There is only one file on disk, and it holds
  whichever dump finished last.
- The overwritten backup still shows **Succeeded**, with its original SHA-256
  (`9cb3e360…`). The file now hashes to the other backup's value (`f8f622d0…`).
- **Verify checksum** and every restore of that backup are refused. The
  checksum guard works, but the backup is gone.
- Deleting either backup deletes the shared file. In the walkthrough,
  deleting the damaged MariaDB backup removed the MySQL backup's artifact, and
  the MySQL backup now says "The artifact is no longer available". The
  delete-many confirmation page showed "828 bytes" (MariaDB's recorded size)
  while it actually deleted MySQL's 786-byte file, with no warning that the
  file was shared.

**Evidence:**
[restore refused](images/issue-02-mariadb-artifact-overwritten-restore-refused.jpg),
[checksum mismatch](images/issue-02b-mariadb-checksum-mismatch.jpg),
[MySQL artifact deleted](images/issue-02c-mysql-artifact-deleted-with-mariadb.jpg),
[delete-many confirmation](images/58-backups-delete-many-confirm.jpg).
Executions `ca397266…` and `1372351f…` (same target, same second, different
recorded SHA-256, one file).

**Suspected cause**

- `application/src/main/java/com/hoangluongtran0309/dbbackup/application/backup/RunBackupService.java:266-272`.
  The name is `<artifactBaseName>_<yyyyMMdd_HHmmss>` with one-second
  resolution, and nothing identifies the target or the execution.
- The dump adapters open the destination with `Files.newOutputStream(destination)`,
  whose default options are `CREATE, TRUNCATE_EXISTING`, so an existing file is
  truncated silently. See `adapters/.../mysql/MysqlDumpBackupAdapter.java:73`,
  `adapters/.../mariadb/MariaDbDumpBackupAdapter.java:56` and
  `adapters/.../sqlite/SqliteDumpBackupAdapter.java:61`.
- Remote profiles are **not** affected. Their keys are
  `<prefix>/<targetId>/<executionId>/<file>`.

**Suggested fix:** use the remote layout locally as well
(`BACKUP_DIR/<targetId>/<executionId>/<file>`), or add the execution ID to the
file name. Open artifacts with `StandardOpenOption.CREATE_NEW`, so a clash fails
loudly instead of truncating. Then add a regression test that runs two backups
of the same database name with a fixed clock.

---

## ISSUE-02

**`Dockerfile.oracle.example` produces an image whose Oracle tools cannot start**

- **Severity:** High (every Oracle test, backup and restore fails with the documented image)
- **Area:** Oracle client pack, CI
- **Status:** Fixed in slice 32. The Oracle image supplies the Ubuntu 24.04
  `libaio.so.1` compatibility link and rejects unresolved client dependencies
  at build time. See [ADR-034](../adr/034-oracle-pack-runtime-and-shared-staging-permissions.md).

**Steps to reproduce**

1. Download Instant Client Basic, SQL\*Plus and Tools for Linux x64 (23.26 on
   2026-10-01). Build the image as in the Dockerfile header.
2. Register an Oracle target and press **Test**.

**Expected:** the connection test runs `sqlplus`.

**Actual:** *Connection failed:
`/opt/oracle/instantclient/sqlplus: error while loading shared libraries:
libaio.so.1: cannot open shared object file: No such file or directory`.*
`ldd` reports `libaio.so.1 => not found` for `sqlplus`, `expdp` and `impdp`.

**Evidence:** [screenshot](images/issue-04-oracle-libaio-missing.jpg).

**Cause:** `Dockerfile.oracle.example:24` installs `libaio1t64`. That is Ubuntu
24.04's renamed package, and it ships only `libaio.so.1t64`. Oracle's binaries
link against `libaio.so.1`. CI did not catch this because `dbbackup-ci:oracle`
contains stub scripts (`#!/bin/sh` / `exit 0`) instead of the real binaries.

**Suggested fix:** after the install, add
`ln -s /usr/lib/x86_64-linux-gnu/libaio.so.1t64 /usr/lib/x86_64-linux-gnu/libaio.so.1`
(Oracle's documented workaround for Ubuntu 24.04). Also add a build-time check,
for example `! ldd /opt/oracle/instantclient/sqlplus | grep -q 'not found'`. The
walkthrough used a patched copy containing exactly that symlink.

---

## ISSUE-03

**PostgreSQL 17 servers cannot be backed up with the shipped image**

- **Severity:** Medium
- **Area:** PostgreSQL engine, packaging
- **Status:** Fixed in slice 33. The image and CI use PGDG client 17, **Test**
  rejects a server newer than its configured `pg_dump`, and the release-shaped
  E2E image backs up PostgreSQL 17. See
  [ADR-035](../adr/035-postgresql-17-client-and-version-preflight.md).

**Steps to reproduce:** register a target on `postgres:17-alpine`. **Test**
reports *Connected*. Press **Back up now**.

**Expected:** a backup, or a connection test that warns about the version
mismatch before the first backup.

**Actual:** *pg_dump exited with 1: pg_dump: error: aborting because of server
version mismatch — server version: 17.11; pg_dump version: 16.15 (Ubuntu
16.15-0ubuntu0.24.04.1)*.

**Evidence:** [screenshot](images/issue-01-postgres17-backup-failed.jpg).

**Notes:** the base image takes `postgresql-client` from Ubuntu noble
(`Dockerfile:52`), which is major 16. The project's own `docker-compose.yml`
uses `postgres:17-alpine` for metadata, and `DBBACKUP_VERIFICATION_POSTGRESQL_IMAGE`
defaults to `postgres:17-alpine`. `docs/deployment.md:313-318` explains that
client compatibility is the operator's job, but not which major the image
ships.

**Suggested fix:** install a current client (for example from the PGDG apt
repository; `pg_dump` 17/18 can dump older servers). At minimum, state the
shipped major in the docs and make **Test** compare `server_version_num` with
`pg_dump --version`.

---

## ISSUE-04

**Oracle Data Pump staging needs a group shared with the Oracle server; this is undocumented and the error hides it**

- **Severity:** Medium
- **Area:** Oracle backup and restore, deployment docs
- **Status:** Fixed in slice 32. The connection test probes the shared staging
  directory in both directions, deployment documents the shared group/setgid
  requirement, and permission failures include ownership and mode. See
  [ADR-034](../adr/034-oracle-pack-runtime-and-shared-staging-permissions.md).

**Steps to reproduce:** follow `docs/deployment.md:236-251`. Mount one directory
at `/srv/dbbackup/oracle-datapump` in the Oracle server and at
`ORACLE_DATAPUMP_ROOT` in the application, create the directory object, and
grant it. **Test** passes. Run a backup.

**Actual**

- Backup: *Oracle Data Pump did not produce a readable dump file:
  /var/lib/dbbackup/oracle-datapump/dbbackup-exp-….dmp*. `expdp` itself
  succeeded. Data Pump always creates files as `0640 oracle:oinstall`
  (54321:54321), and the application (uid 10001, gid 999) gets `EACCES`.
- Restore: the application copies the artifact into the share as `0640`
  `10001:999`, and `impdp` fails with *ORA-31640 … ORA-27041 … Linux-x86_64
  Error: 13: Permission denied*. This surfaced as "Oracle Data Pump archive
  preflight failed".

**Evidence:** [screenshot](images/issue-05-oracle-dump-not-readable.jpg).
The copy happens in `adapters/.../oracle/OracleDataPumpFiles.java:97`.

**Workaround used:** add the app container to group 54321 (`group_add`), then
`chgrp 54321` the share and `chmod 2777` it (setgid), so new files inherit
`oinstall`.

**Suggested fix:** document the ownership requirement (a common group plus a
setgid directory, or an ACL). Make the connection-test probe check both
directions, Oracle writing a file the app can read and the reverse. Report the
real cause in the error, for example "not readable by uid 10001: owner 54321,
mode 0640".

---

## ISSUE-05

**Oracle artifacts are named after the service, so every schema in one PDB shares a prefix**

- **Severity:** Medium (it makes ISSUE-01 much more likely for Oracle)
- **Area:** Oracle artifact naming
- **Status:** Fixed in slice 31. The shared prefix can no longer cause a collision, because each execution has its own directory ([ADR-033](../adr/033-local-artifacts-live-per-target-and-execution.md)). The documented `<service>_<timestamp>.dmp` name is unchanged.

The backup of schema `SHOP` was named `FREEPDB1_20261001_035300.dmp`
([screenshot](images/66-backup-oracle-succeeded.jpg)).
`DatabaseTarget.artifactBaseName()` (`core/.../model/DatabaseTarget.java:223`)
returns `databaseName`, which is the service name for Oracle. Meanwhile
`backupNamespace()` correctly uses the username. Two schemas in the same
service that are backed up in the same second collide (see ISSUE-01). The
README describes the name as `<service>_<timestamp>.dmp`, so this matches the
documentation, but the file name does not identify the schema.

**Suggested fix:** use `<service>_<schema>_<timestamp>.dmp`, or let the
ISSUE-01 fix (per-execution directories) cover it.

---

## ISSUE-06

**Chrome offers the database credentials on the operator sign-in page**

- **Severity:** Low (depends on the browser)
- **Area:** target form, sign-in
- **Status:** Fixed in slice 35. Target forms now use distinct database
  credential field names with autocomplete disabled, while operator sign-in
  keeps the standard login fields. See
  [ADR-037](../adr/037-console-layout-and-credential-autofill.md).

After registering targets in Chrome and signing out, the sign-in form was
pre-filled with the *database* user `shop` and its password
([screenshot](images/issue-03-signed-out-login-prefilled-with-db-credentials.jpg)).
The target form uses `name="username"` plus a `type="password"` field
(`web/src/main/resources/templates/database/form.html:105,142`), the same
field names as the sign-in form. Chrome treats it as a sign-up for this
origin, even though the fields already use `autocomplete="off"` and
`autocomplete="new-password"`. Database credentials can end up in the browser's
password manager under the console's origin, and get offered for the operator
login.

**Suggested fix:** give the target form's fields names that don't look like a
sign-in, for example `dbUsername`, or put `autocomplete="off"` on the form. Then
re-check in Chrome and Firefox.

---

## ISSUE-07

**Targets and storage tables scroll sideways at 1366 px and hide the action buttons**

- **Severity:** Low
- **Area:** `database/list.html`, `storage/list.html`
- **Status:** Fixed in slice 35. Targets and storage switch to labelled cards
  at laptop widths, with all row actions visible and wrapped. See
  [ADR-037](../adr/037-console-layout-and-credential-autofill.md).

At a 1366×683 viewport (a common laptop size), the targets table needs
horizontal scrolling even with one row. **Back up now** is cut off, and
**Schedule / Retention / Notifications / Edit / Remove** are off-screen. The
scrollbar sits at the bottom of a long table, and once you scroll right the
name column is no longer visible, so you can't tell which row a button belongs
to. The storage list behaves the same once three profiles exist (**Remove** is
cut off). See [targets](images/09-targets-tested-failed.jpg),
[scrolled](images/10-targets-tested-all-engines.jpg) and
[storage](images/43-storage-all-profiles-passed.jpg).

**Suggested fix:** collapse the secondary actions into a menu, wrap them onto
a second line, or switch to the existing `responsive-cards` layout
(`static/css/app.css:305`) at a wider breakpoint.

---

## ISSUE-08

**Page headers with many buttons squeeze the title**

- **Severity:** Low
- **Area:** `execution/detail.html` header, Storage page header
- **Status:** Fixed in slice 35. Storage and backup-detail actions move below
  the title at laptop widths. See
  [ADR-037](../adr/037-console-layout-and-credential-autofill.md).

On a successful backup's page, six buttons sit next to the title, so
`shop-postgres` breaks into "shop- / postgres" and **Delete** wraps onto its
own row ([screenshot](images/15-backup-checksum-verified.jpg)). The Storage
page wraps **Add Azure profile** the same way
([screenshot](images/38-storage-local-default.jpg)).

**Suggested fix:** let the actions row wrap below the title, or group the
secondary actions together.

---

## ISSUE-09

**API: omitting `verifyAfterBackup` returns a raw Jackson error**

- **Severity:** Low
- **Area:** `POST /api/v1/targets`
- **Status:** Fixed in slice 34. Omitted and JSON `null` values now default to
  `false`, and malformed JSON receives a stable public message. See
  [ADR-036](../adr/036-stable-operator-contracts.md).

```console
$ curl -u admin:*** -X POST http://localhost:8080/api/v1/targets \
    -H 'Content-Type: application/json' -d '{"name":"","engine":"MYSQL"}'
{"ok":false,"data":null,"error":{"code":"VALIDATION_ERROR","message":"JSON parse error: Cannot map `null` into type `boolean` (set `DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES` to 'false' to allow)","field":null}}
```

The request record uses a primitive `boolean verifyAfterBackup`
(`web/.../api/OperatorApiController.java:294`). That makes an option that
looks optional effectively required, and the message exposes library
internals. **Suggested fix:** default it to `false` (use `Boolean` and map
`null` to `false`), or return a field error on `verifyAfterBackup`.

---

## ISSUE-10

**API: validation returns one error per request, not the first in field order**

- **Severity:** Low
- **Area:** HTTP API validation
- **Status:** Fixed in slice 34. Validation responses add an ordered `errors`
  array while retaining the first `message` and `field` for compatibility. See
  [ADR-036](../adr/036-stable-operator-contracts.md).

With `{"name":"","engine":"MYSQL","verifyAfterBackup":false}`, the response is
`"Password is required"` with `"field":"password"`, although `name`, `host`,
`database` and `username` are also missing. The web form shows every error at
once ([screenshot](images/05-target-validation-errors.jpg)). An API client has
to fix one field per round trip. **Suggested fix:** return every field error,
for example as an `errors` array, or at least the first one in field order.

---

## ISSUE-11

**Webhook `occurredAt` is a decimal epoch number**

- **Severity:** Low
- **Area:** `adapters/.../notification/WebhookNotificationAdapter.java:31`
- **Status:** Fixed in slice 34. Generic webhooks now encode `occurredAt` as a
  lossless UTC ISO-8601 string. See
  [ADR-036](../adr/036-stable-operator-contracts.md).

The received body contained `"occurredAt":1790825725.003285481`. The adapter's
`ObjectMapper` keeps Jackson's default `WRITE_DATES_AS_TIMESTAMPS`, while the
CLI turns it off (`cli/.../DbBackupCli.java:75`). JavaScript consumers lose the
nanosecond digits, and ADR-028 describes a "stable JSON object" without saying
which format this field uses. **Suggested fix:** emit ISO-8601 UTC
(`2026-10-01T03:35:25.003285481Z`), as the email body already does, and
document it.

*Observation, not a bug:* Java's HTTP client sends `Upgrade: h2c` on plain
`http://` webhooks. Some strict receivers reject that. Forcing HTTP/1.1 would
avoid it.

---

## ISSUE-12

**Azure profiles say "bucket" where Azure has a "container"**

- **Severity:** Low
- **Area:** `storage/form.html:7`, `storage/list.html:14`
- **Status:** Fixed in slice 35. Azure forms say `container`, and the mixed
  storage list uses `Bucket / container` with provider-specific card labels.
  See [ADR-037](../adr/037-console-layout-and-credential-autofill.md).

The Azure form's subtitle says "The bucket must already exist", although the
field is labelled *Container*. The storage list's column header is *Bucket*
for every provider ([form](images/42-storage-azure-form.jpg),
[list](images/43-storage-all-profiles-passed.jpg)). **Suggested fix:** use
provider-specific wording, or a neutral header such as "Bucket / container".

---

## ISSUE-13

**The cron hint is fixed text**

- **Severity:** Low
- **Area:** `schedule/form.html:42`
- **Status:** Fixed in slice 35. The fixed cron expression is explicitly
  introduced as an example rather than a description of the entered value.
  See [ADR-037](../adr/037-console-layout-and-credential-autofill.md).

The hint under the cron field always reads "`0 0 2 * * ?` means every day at
02:00:00", even when editing a schedule whose expression is `0 * * * * ?`
([screenshot](images/31-schedule-edit.jpg)). It reads like a description of
the entered value. **Suggested fix:** label it as an example ("For example,
…"), or show the next few fire times for the expression entered.

---

## ISSUE-14

**CLI `--output text` (the default) prints JSON**

- **Severity:** Low
- **Area:** `cli/.../DbBackupCli.java:232`
- **Status:** Fixed in slice 34. Text mode now renders collections as tables,
  details as key/value sections and aggregate validation errors as field lines.
  JSON mode remains the automation format. See
  [ADR-036](../adr/036-stable-operator-contracts.md).

`dbbackup --help` lists `--output text|json` with `text` as the default, but
text mode prints the pretty-printed JSON of `data` (for example
`dbbackup target list`). The difference from `json` is only the missing
envelope. **Suggested fix:** print short tables for `list` and `show`, or
document that `text` means "unwrapped JSON".

---

## ISSUE-15

**`Dockerfile.oracle.example` cannot build with Oracle Instant Client 23.26**

- **Severity:** High (the documented Oracle pack cannot be built)
- **Area:** `Dockerfile.oracle.example:27-33`
- **Status:** Fixed in slice 37. The Instant Client library path is present
  during both the build-time dependency check and normal runtime; CI uses
  ELF clients linked to a colocated library without RPATH/RUNPATH.

**Steps to reproduce:** download the current Linux x64 Instant Client Basic,
SQL*Plus and Tools archives (23.26.2.0.0), copy `instantclient_23_26/*` into
`oracle-client/` as the Dockerfile header says, and run
`docker build -f Dockerfile.oracle.example --build-arg BASE_IMAGE=dbbackup:base .`.

**Actual:** the build stops in the dependency check:

```
libsqlplus.so => not found
libclntsh.so.23.1 => not found
libclntshcore.so.23.1 => not found
libnnz.so => not found
```

None of `sqlplus`, `expdp` or `impdp` in this release has an `RPATH` or
`RUNPATH` (`readelf -d` shows none), so `ldd` resolves the Instant Client
libraries only through `LD_LIBRARY_PATH`. The image sets that variable with
`ENV` *after* the `RUN` that calls `ldd`. The CI image `dbbackup-ci:oracle`
uses stub tools, so CI does not catch it.

**Workaround used in the tour:** a copy of the Dockerfile in which the check
runs `LD_LIBRARY_PATH=/opt/oracle/instantclient ldd "$path"`. The resulting
image backed up, restored and verified Oracle Free 23 successfully.

**Fix:** `LD_LIBRARY_PATH` is now part of the image before `ldd` runs. The CI
fixture links all three clients to a shared library that is available only
through that path and asserts that no client has RPATH or RUNPATH.

---

## ISSUE-16

**The targets and storage tables still scroll sideways between 1400 px and about 1560 px**

- **Severity:** Low
- **Area:** `web/src/main/resources/static/css/app.css:292` (`@media (max-width: 1399px)`)
- **Status:** Fixed in slice 38. Targets and storage use a 1540-pixel named
  size container, so their card layout follows available content width rather
  than the viewport. See [ADR-038](../adr/038-wide-tables-query-their-container.md).

Slice 35 switches the targets and storage tables to labelled cards below
1400 px. At a 1440 px wide window the main column is 1090 px wide, but the
targets table needs 1540 px, so it scrolls sideways again. Only **Test** and
**Back up now** are visible; **Schedule**, **Retention**, **Notifications**,
**Edit** and **Remove** sit off-screen behind a horizontal scrollbar
([screenshot](../tour/images/04-targets-tested.jpg)). The storage table shows the same scrollbar
([screenshot](../tour/images/14-storage-profiles-passed.jpg)).

**Fix:** a wrapper exposes the available inline size as a named CSS container.
Below the table's measured 1540-pixel minimum the same rows become labelled
cards; at or above it the dense table remains available.

---

## ISSUE-17

**Lifecycle webhook payloads send `channel.id` and `channel.name` as `null`**

- **Severity:** Low
- **Area:** `application/.../notification/NotificationDispatcher.java:55-76`
- **Status:** Open, assigned to slice 39.

ADR-028 promises that the generic webhook body carries the channel identity.
A **Send test** delivery does carry it. A real `BACKUP_SUCCESS` delivery for
the same channel arrives as
`"channel": {"id": null, "name": null}`
([screenshot](../tour/images/24-webhook-delivery.jpg)). `publishBackup`,
`publishRestore` and `publishVerification` build one `NotificationMessage`
with `null, null` for the channel fields and send that same message to every
subscribed channel.

**Suggested fix:** in `publish`, copy the message with the channel's id and
name before `send(channel, ...)`, and assert the field in the webhook adapter
test for a lifecycle event.

---

## Not counted as issues (environment limits)

- **Slack and Telegram** were not test-sent, because they would contact real
  external services. Slack's URL validation was checked.
- **`dbbackup-ci:oracle`** contains synthetic ELF Oracle tools. They exercise
  dynamic library discovery and image structure, but not Oracle's proprietary
  client implementation.
- **MinIO** could no longer be pulled from Docker Hub or quay.io without
  credentials, so S3 was tested with `adobe/s3mock`.
- **SELinux (Fedora, enforcing)** denied the containers access to their bind
  mounts and the application container access to `/var/run/docker.sock`. The
  feature tour relabelled its mounts with `:z` and ran the application with
  `security_opt: [label=disable]`. This is host policy, not an application
  defect; [deployment](../deployment.md) now describes it.
