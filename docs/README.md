# Documentation

- [architecture/overview.md](architecture/overview.md) — the four modules and
  why the dependency direction is what it is.
- [deployment.md](deployment.md) — the image, the compose file, and what an
  operator has to decide.
- [branching.md](branching.md) — GitFlow: which branch comes from where, and
  the exact steps for a release and a hotfix.
- [http-api.md](http-api.md) — `/api/v1` resources, authentication, envelopes
  and their matching CLI commands.
- [walkthrough/ISSUES.md](walkthrough/ISSUES.md) — problems found by the
  0.19.0 browser walkthrough, each tied to the ROADMAP slice that fixes it.
- [../SECURITY.md](../SECURITY.md) — private vulnerability reporting, supported
  versions and the release supply-chain policy.
- [adr/](adr/) — architecture decision records, numbered from 001 in the order
  the decisions were made.

The current last decision is
[ADR-034](adr/034-oracle-pack-runtime-and-shared-staging-permissions.md), which
makes the optional Oracle client image loadable on Ubuntu 24.04 and requires
bidirectional access to its shared Data Pump staging directory.

These pages describe the code that exists. When a slice changes the code, it
changes these files in the same commit.
