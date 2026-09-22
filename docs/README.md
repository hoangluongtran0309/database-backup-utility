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
[ADR-023](adr/023-quartz-triggers-are-derived-from-backup-schedules.md), which
defines recurring backups and why Quartz triggers are rebuilt from the metadata
schedule table.

These pages describe the code that exists. When a slice changes the code, it
changes these files in the same commit.
