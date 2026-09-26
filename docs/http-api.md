# Operator HTTP API

The administrative API is rooted at `/api/v1`. It uses HTTP Basic with the
same `OPERATOR_USERNAME` and plaintext password whose bcrypt hash configures
the console. It is stateless and returns JSON for success and failure:

```json
{"ok": true, "data": {}, "error": null}
```

```json
{"ok": false, "data": null, "error": {"code": "VALIDATION_ERROR", "message": "...", "field": null}}
```

HTTP status remains authoritative: 400 validation, 401/403 authentication and
authorization, 404 missing resource, 409 state/name conflict, and 202 for an
accepted asynchronous job. Backup, restore and verification status is read
from its detail resource. All timestamps are UTC ISO-8601 values.

## Resources

| Resource | Operations | CLI group |
| --- | --- | --- |
| `/targets` | list, show, create, edit, delete, connection test | `target` |
| `/storage-profiles` | list, show, create, edit, delete, connection test | `storage` |
| `/notification-channels` | list, show, create, edit, delete, send test | `notification` |
| `/targets/{id}/notifications` | read or replace target subscriptions | `subscription` |
| `/schedules` | list, show, create, edit, delete | `schedule` |
| `/retention` | list; read, set or disable by target | `retention` |
| `/backups` | paged list, detail, start by target, checksum, restore test, artifact download, delete | `backup` |
| `/restores` | paged list, detail and confirmed start | `restore` |
| `/verifications/{id}` | verification execution detail | polled by `backup test-restore` |

Collection paths above are relative to `/api/v1`. List endpoints use
`page` (one-based) and `pageSize` (1–100) where history can grow without bound.
Deleting a target requires `confirmBackups=true` when backup history exists.
Starting a restore requires `confirmation` to equal the destination target's
name exactly.

The create/edit representations use the same camel-case fields as their safe
response projections. Enum values use their documented uppercase names. Secret
fields are write-only: `password`, `botToken`, `webhookUrl`,
`secretAccessKey`, `serviceAccountJson` and `accountKey`. Responses expose only
`secretConfigured` where needed.

## CLI mapping and credentials

Run `dbbackup help` for all groups and actions. Resource options are kebab-case
forms of API fields; UUID selectors use `--id` or `--target-id`. Repeat
`--subscription CHANNEL_UUID:EVENT,EVENT` to replace target subscriptions.

API credentials come from `DBBACKUP_API_USERNAME` and
`DBBACKUP_API_PASSWORD`, or the corresponding global options. Resource secrets
must use `--secret FIELD=ENV_VAR`; known secret fields passed directly are
rejected. `--output json` prints the full envelope for automation. Exit codes
are `0` success, `2` local/remote validation, `3` not found, `4` conflict, `5`
authentication/operation failure and `70` transport or internal CLI failure.
