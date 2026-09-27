# Security policy

## Supported versions

Only the newest published release receives security fixes. Upgrade before
reporting a problem that is already fixed in a newer release.

## Reporting a vulnerability

Use GitHub's **Report a vulnerability** private advisory form for this
repository. Do not open a public issue, pull request or discussion containing
an exploit, credential, database dump or other sensitive evidence.

Include the affected version, deployment shape, reproduction steps, impact and
any suggested mitigation. Reports are acknowledged and investigated on a
best-effort basis; a disclosure date is agreed with the reporter after a fix or
safe mitigation is available.

## Supply-chain policy

- Pull requests must pass Maven verification, CodeQL, dependency review,
  container scanning and the API/CLI end-to-end path.
- GitHub Actions are pinned to immutable commit SHAs. Dependabot proposes
  reviewed updates to those pins and to Maven and Docker dependencies.
- Trivy blocks fixable `HIGH` and `CRITICAL` findings in every image CI can
  build. A temporary exception must name the vulnerability, explain why it is
  safe to defer, be restricted to an affected path or package URL and expire
  within 90 days. CI validates these fields before applying the ignore file.
- A release publishes checksums, SPDX SBOMs and GitHub artifact attestations.
  Verify those materials before deploying an image or executable JAR obtained
  from anywhere other than this repository's release page or GHCR package.

The operator-built Oracle and SQL Server variants can contain proprietary or
site-supplied binaries that CI cannot inspect. The operator must scan the final
derived image and keep those client packs patched.
