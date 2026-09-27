# ADR-032: Protected branches require independent gates and releases are attested

## Status

Accepted.

## Context

The original pipeline ran full Maven verification and built the example
images, but one monolithic job was only advisory. GitHub Actions references
used mutable major tags, `develop` still accepted direct pushes, no workflow
proved the packaged image through the public HTTP API, and releases had no
machine-verifiable relationship between source, image and downloadable JARs.

The adapter integration tests already exercise all seven database engines.
Repeating that matrix through a deployed application would make pull requests
slower without testing a new boundary. The missing boundary is the assembled
image, metadata migration, Basic-authenticated API and bundled CLI working as
one system.

## Decision

CI exposes stable, independent checks for workflow lint, full `mvn verify`,
container build/scan, CodeQL, dependency review and one Compose E2E path. The
E2E path uses PostgreSQL for application metadata and mounted SQLite source and
destination files. Through the bundled `dbbackup` command it proves
authentication, target creation, backup, checksum, artifact download,
isolated restore verification and cross-target restore, plus representative
failure and corruption paths. SQLite requires no root-equivalent Docker socket.

Every external GitHub Action is pinned to a full commit SHA. Dependabot groups
weekly Maven, Actions and Docker updates. Trivy blocks fixable high and
critical image findings. A temporary Trivy exception must carry a CVE, a
reason, an affected path or package URL and an expiry no more than 90 days in
the future; workflow lint validates that policy. CodeQL and dependency review
publish their native security results. `main` and `develop` accept only
merge-commit pull requests that pass all required checks.

An annotated `vMAJOR.MINOR.PATCH` tag reachable from `main` starts delivery.
The tag must equal the non-snapshot Maven version and have curated notes in
`docs/releases/MAJOR.MINOR.PATCH.md`. The workflow publishes the Linux AMD64
base image to GHCR, executable web and CLI JARs, checksums and SPDX SBOMs, then
records build-provenance and SBOM attestations. Oracle and SQL Server variants
remain operator-built because their client packs and terms are deployment
specific.

## Consequences

- A feature, release, hotfix or release merge-back reaches a protected branch
  only through a PR; direct merge-back pushes are no longer permitted.
- Release notes are reviewed as source before the tag makes them public, and a
  tag cannot silently release a snapshot or a commit outside `main`.
- Pull requests spend additional time building and scanning images and running
  a real deployment, but the required checks cover distinct boundaries.
- The published image is Linux AMD64 only. Optional derived images and
  operator-provided Oracle clients need their own final-image scan.
- Browser behavior remains covered by MVC/security tests. The E2E workflow is
  intentionally API/CLI based and does not introduce a Node/browser toolchain.
