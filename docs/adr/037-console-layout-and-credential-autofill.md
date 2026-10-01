# ADR-037: Wide console content becomes cards at laptop widths

## Status

Accepted.

## Context

The targets and storage tables combine operational status with several actions.
Inside the fixed console shell they overflow a 1366-pixel laptop viewport and
hide the actions behind a horizontal scrollbar. Headers with several actions
also squeeze their title into narrow fragments.

The target form used the same `username` and `password` field names as the
operator sign-in form. Browser password managers could therefore save database
credentials as credentials for the console origin and offer them at sign-in.

## Decision

Targets and storage retain tables above 1399 pixels. At or below that viewport
width, only those two wide tables render their rows as labelled cards and wrap
all row actions. Storage and backup-detail headers put their action group below
the title at the same breakpoint. Existing mobile navigation and the compact
tables elsewhere are unchanged.

The target form uses the console-only field names `databaseUsername` and
`databasePassword`, with autocomplete disabled on the form and both inputs.
The form DTOs map those values back to the existing application command fields;
the operator sign-in form keeps the standard `username` and `password` names.

Storage wording distinguishes an Azure container from an S3 or GCS bucket, and
the schedule form labels its fixed cron expression as an example rather than a
description of the current value.

## Consequences

- Laptop operators see the identity, status and actions of one resource
  together without horizontal scrolling.
- Wide desktop views keep the denser table presentation, while the existing
  phone card layout continues to work.
- Console form parameter names change, but the HTTP API, CLI, domain model and
  persisted credentials do not.
- Browser autofill remains heuristic, but database fields no longer imitate
  the operator sign-in contract.
