# ADR-036: Keep operator API, CLI and webhook contracts explicit

## Status

Accepted.

**Amends** ADR-028 for webhook timestamps and ADR-031 for API errors and CLI
text output.

## Context

The versioned operator API treated an omitted `verifyAfterBackup` as a Jackson
primitive-mapping failure, exposed that library detail, and returned only the
first invalid field. The CLI's documented text mode printed unwrapped JSON.
Generic webhooks serialized `Instant` as a decimal epoch value whose
nanoseconds are unsafe in common JavaScript number implementations.

`/api/v1` is already an automation contract, so improving validation must not
remove the existing `error.code`, `error.message` or `error.field` fields.

## Decision

`verifyAfterBackup` is optional on target requests; absent and JSON `null`
both mean `false`. Write-request validation reports every independently
detectable error in declaration order as `error.errors`, whose entries contain
`field` and `message`. The legacy `message` and `field` repeat the first entry.
Malformed JSON receives a stable message instead of a parser exception.

The CLI's default text mode renders object collections as plain tables and
details as key/value sections. Null is `-`, empty collections are `(none)`,
and validation failures list every returned field error. JSON mode continues
to print the complete API envelope and remains the automation format.

Generic webhooks encode `occurredAt` as a UTC ISO-8601 string using the full
precision of the source `Instant`. No other webhook field or transport
behavior changes.

## Consequences

- Existing API clients can continue reading the first error while newer ones
  fix all invalid fields in one request cycle.
- Text output is intended for operators and may grow with the public
  projection; scripts must request JSON.
- Webhook consumers receive a lossless timestamp such as
  `2026-10-01T03:35:25.003285481Z` instead of a decimal number.
- HTTP version selection, webhook retries and signatures remain out of scope.
