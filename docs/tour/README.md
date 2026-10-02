# Feature tour media

Screenshots and animations recorded for [../FEATURES.md](../FEATURES.md) on
2026-10-02, against `develop` at `5282738` (0.20.0-SNAPSHOT), with all seven
engines, the S3, GCS and Azure emulators, Mailpit and restore verification
enabled. Everything shown belongs to a throwaway environment.

The original screenshots are 1440 × 723 JPEGs. Follow-up responsive-layout
evidence is full-page and 1440 px wide. Animations are 1080 px wide GIFs
without action labels, so nothing typed into a password field appears in them.

## Animations (`gifs/`)

| File | Flow |
| --- | --- |
| `sign-in.gif` | Rejected sign-in, then a successful one |
| `add-and-test-target.gif` | Validation errors, registering a PostgreSQL target, testing its connection |
| `run-backup.gif` | Back up now, live detail page, automatic restore verification, checksum check |
| `restore.gif` | Restoring a MySQL backup into a scratch database after typing its name |
| `verify-restore.gif` | An isolated test restore of a MySQL backup |
| `storage-profile.gif` | Adding an S3 profile, a failing test, enabling path-style access, a passing test |
| `schedule.gif` | Creating a per-minute schedule in a named time zone |
| `notifications.gif` | Creating a webhook channel, sending a test, subscribing a target, the delivered event |

## Screenshots (`images/`)

| Files | Area |
| --- | --- |
| `01` | Sign-in |
| `03`–`05`, `16`, `28`, `30`, `37` | Targets: validation, connection tests, laptop cards, edit, removal, container-query follow-up |
| `06`–`08`, `27`, `29` | Backups: detail, checksum, all engines, delete many, a failure |
| `09`–`11` | Restores |
| `12`, `35`, `36` | Restore verification (MySQL, Oracle, SQL Server) |
| `13`–`15`, `17`, `38` | Storage profiles, a backup stored in S3, and the container-query follow-up |
| `18`–`19` | Schedules |
| `20`–`21` | Retention |
| `22`–`26`, `39` | Notifications, subscriptions, webhook and email deliveries, channel-identity follow-up |
| `31`–`33` | CLI and HTTP API |
| `34` | Light theme |

Issue evidence from the first walkthrough lives separately in
[../walkthrough/images/](../walkthrough/images/).
