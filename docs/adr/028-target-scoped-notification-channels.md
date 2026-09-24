# ADR-028: Notification subscriptions belong to database targets

## Status

Accepted.

## Context

Backup and restore work is already asynchronous and target-centric. Operators
need prompt delivery of lifecycle outcomes, but a notification outage must not
rewrite an execution result or keep a worker occupied indefinitely. Different
targets may also have different owners and escalation policies. A global list
of recipients would send too much information to the wrong people, while a
schedule-scoped model would miss manual backups and restores.

## Decision

A reusable `NotificationChannel` is one named Telegram, Slack, Email or generic
Webhook destination. Each `DatabaseTarget` subscribes to any number of channels
and chooses among the six public events: backup and restore started, succeeded
and failed. `TEST` is reserved for the operator's explicit **Send test** action
and cannot be persisted in a subscription. A newly displayed channel starts
with the two failure events selected.

Telegram bot tokens, Slack incoming-webhook URLs and generic webhook URLs are
AES-256-GCM encrypted with the existing deployment key. The application
decrypts them immediately before dispatch. Channel type is immutable and an
empty secret on edit preserves the existing ciphertext; plaintext secrets are
never rendered back into the console. Slack accepts only official HTTPS
incoming-webhook hosts. Generic webhooks accept HTTP or HTTPS. Email recipients
are stored per channel, while SMTP transport is deployment-wide and optional.

Delivery is synchronous on the backup or restore worker after the relevant
execution state is durable. `STARTED` is emitted before the engine client is
invoked; exactly one terminal success or failure is emitted after that state is
stored. Queue rejection and startup repair emit failure. Retention failure does
not change a successful backup and creates no extra event. Restore dispatch
uses the destination target's subscriptions, while its message identifies both
source and destination targets.

`NotificationDispatcher` filters subscriptions and isolates every delivery.
One channel failure is logged and the remaining channels are attempted. A
missing adapter or notification configuration never changes the backup or
restore result. There is no retry or outbox. HTTP adapters use a three-second
connect timeout and five-second request timeout; SMTP connect, read and write
timeouts are five seconds. Interrupted HTTP delivery restores the thread's
interrupt flag.

The generic webhook body is a stable JSON object containing `event`, `isTest`,
`occurredAt`, channel identity, source and destination target identities,
backup and restore execution IDs, status and a sanitized error message. Human
messages and timestamps use UTC. Known password, token, authorization and URI
credential shapes are redacted before an execution error reaches an adapter.

V18 creates `notification_channels` and
`database_target_notification_channels`. Channel names are unique after
case-folding and trimming. A target deletion cascades its subscriptions; a
channel deletion is restricted while any target uses it. Subscription events
are stored as sorted enum names and validated again when loaded.

## Consequences

- Operators manage channels under **Setup → Notifications** and subscriptions
  from each target.
- `DBBACKUP_SMTP_HOST` enables Email. With no host the application still starts;
  Email tests and deliveries report that SMTP is unavailable.
- “Send test” performs a real delivery and persists its latest result, but
  ordinary deliveries have no history table.
- Delivery is best-effort and bounded, but it adds up to one transport timeout
  per selected channel to the worker after the database operation has finished.
- No custom webhook headers or signatures, retries, outbox, schedule/global
  subscriptions, SMTP health indicator or preflight/verification events are
  introduced by this decision.
