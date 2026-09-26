# Documentation

- [architecture/overview.md](architecture/overview.md) — the four modules and
  why the dependency direction is what it is.
- [deployment.md](deployment.md) — the image, the compose file, and what an
  operator has to decide.
- [branching.md](branching.md) — GitFlow: which branch comes from where, and
  the exact steps for a release and a hotfix.
- [http-api.md](http-api.md) — `/api/v1` resources, authentication, envelopes
  and their matching CLI commands.
- [adr/](adr/) — architecture decision records, numbered from 001 in the order
  the decisions were made.

The current last decision is
[ADR-031](adr/031-cli-over-the-operator-http-api.md), which keeps the CLI
stateless and makes the web application the sole owner of scheduling and jobs.

These pages describe the code that exists. When a slice changes the code, it
changes these files in the same commit.
