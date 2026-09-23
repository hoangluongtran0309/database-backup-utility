# Documentation

- [architecture/overview.md](architecture/overview.md) — the four modules and
  why the dependency direction is what it is.
- [deployment.md](deployment.md) — the image, the compose file, and what an
  operator has to decide.
- [branching.md](branching.md) — GitFlow: which branch comes from where, and
  the exact steps for a release and a hotfix.
- [adr/](adr/) — architecture decision records, numbered from 001 in the order
  the decisions were made.

The current last decision is
[ADR-026](adr/026-google-cloud-storage-profiles.md), which adds native Google
Cloud Storage profiles and resumable upload to the provider-neutral artifact
workflow established by ADR-025.

These pages describe the code that exists. When a slice changes the code, it
changes these files in the same commit.
