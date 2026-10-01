# Documentation

- [FEATURES.md](FEATURES.md) — every feature of the console, API and CLI,
  with screenshots and animations from a run against all seven engines.
- [tour/](tour/) — the screenshots and GIFs that guide uses, and how they
  were recorded.
- [architecture/overview.md](architecture/overview.md) — the four modules and
  why the dependency direction is what it is.
- [deployment.md](deployment.md) — the image, the compose file, and what an
  operator has to decide.
- [branching.md](branching.md) — GitFlow: which branch comes from where, and
  the exact steps for a release and a hotfix.
- [http-api.md](http-api.md) — `/api/v1` resources, authentication, envelopes
  and their matching CLI commands.
- [walkthrough/ISSUES.md](walkthrough/ISSUES.md) — problems found by the
  0.19.0 browser walkthrough and the 0.20.0 feature tour, each tied to the
  ROADMAP slice that fixes it.
- [../SECURITY.md](../SECURITY.md) — private vulnerability reporting, supported
  versions and the release supply-chain policy.
- [adr/](adr/) — architecture decision records, numbered from 001 in the order
  the decisions were made.

The current last decision is
[ADR-037](adr/037-console-layout-and-credential-autofill.md), which keeps wide
console resources usable at laptop widths and separates database credential
fields from the operator sign-in form.

These pages describe the code that exists. When a slice changes the code, it
changes these files in the same commit.
