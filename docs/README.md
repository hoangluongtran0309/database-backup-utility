# Documentation

- [architecture/overview.md](architecture/overview.md) — the four modules and
  why the dependency direction is what it is.
- [deployment.md](deployment.md) — the image, the compose file, and what an
  operator has to decide.
- [branching.md](branching.md) — GitFlow: which branch comes from where, and
  the exact steps for a release and a hotfix.
- [http-api.md](http-api.md) — `/api/v1` resources, authentication, envelopes
  and their matching CLI commands.
- [../SECURITY.md](../SECURITY.md) — private vulnerability reporting, supported
  versions and the release supply-chain policy.
- [adr/](adr/) — architecture decision records, numbered from 001 in the order
  the decisions were made.

The current last decision is
[ADR-032](adr/032-required-ci-gates-and-attested-releases.md), which makes
independent CI/security/E2E checks mandatory and binds releases to their
source, checksums and SBOMs.

These pages describe the code that exists. When a slice changes the code, it
changes these files in the same commit.
