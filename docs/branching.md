# Branching model

GitFlow. Two long-lived branches, three families of short-lived ones.

```
main     ──●───────────────────────●─────────●──▶   only releases, every commit tagged
            \                     /         /
hotfix/*     \                   /       ──●──      from main, back into main and develop
              \                 /       /
release/*      \          ────●─       /            from develop, stabilise, then main
                \        /     \      /
develop  ────────●──●──●────────●────●─────────▶    integration
feature/*      ─●─  ─●─                             from develop, back into develop
```

| Branch | Cut from | Merges into | Lives for |
| --- | --- | --- | --- |
| `main` | — | — | forever; only ever receives merges from `release/*` and `hotfix/*` |
| `develop` | `main` | — | forever |
| `feature/*` | `develop` | `develop` | one slice |
| `release/*` | `develop` | `main` **and** `develop` | one release |
| `hotfix/*` | `main` | `main` **and** `develop` | one urgent fix |

`release/*` and `hotfix/*` are created when they are needed and deleted when
they are merged. There are no standing empty ones.

## Naming

- `feature/<slice-number>-<short-kebab-summary>` — e.g. `feature/03-run-logical-backup`.
  The slice number ties the branch to [ROADMAP.md](../ROADMAP.md).
- `release/<version>` — e.g. `release/0.1.0`. No `v`.
- `hotfix/<version>` — e.g. `hotfix/0.1.1`. The version being produced, not the one being fixed.
- Tags carry the `v`: `v0.1.0`.

Commits follow Conventional Commits: imperative, lower case, subject at most
72 characters.

## Never commit directly to `main` or `develop`

Both receive merges only. Every merge into them is `--no-ff`, so the shape of
the history still shows which commits belonged to which slice — a fast-forward
would flatten that away and make a slice impossible to revert as a unit.

## Working a slice

```bash
git switch develop
git switch -c feature/03-run-logical-backup

# ... work, committing as you go; the slice's docs land in the same commits ...

mvn verify                          # must be green before the branch is offered
git push -u origin feature/03-run-logical-backup
gh pr create --base develop --head feature/03-run-logical-backup
gh pr merge --merge                # after every required check is green
git branch -d feature/03-run-logical-backup
```

## Cutting a release

The version in the parent `pom.xml` is hardcoded — there is no `${revision}`
and no flatten plugin — so the release branch is where it changes, twice.

Both long-lived branches accept changes only through a pull request (see
[what the remote enforces](#what-the-remote-enforces)). A release reaches
`main`, and then returns to `develop`, through merge commits made by GitHub.

```bash
git switch develop
git switch -c release/0.1.0

# Drop -SNAPSHOT. One edit, in the parent pom only; the modules inherit it.
mvn versions:set -DnewVersion=0.1.0 -DprocessAllModules -DgenerateBackupPoms=false
# Add docs/releases/0.1.0.md using the same headings as earlier releases.
mvn verify
git commit -am "chore: release 0.1.0"
git push -u origin release/0.1.0

gh pr create --base main --head release/0.1.0 --title "Release 0.1.0"
gh pr merge --merge          # or "Create a merge commit" in the web UI

# Tag the merge commit GitHub made, and only once it exists: a tag made on a
# local merge points at a commit main will never contain.
git fetch origin
git switch main
git merge --ff-only origin/main
git tag -a v0.1.0 -m "0.1.0"
git push origin v0.1.0

# Pushing the annotated tag starts the release workflow. It validates the tag,
# publishes the GHCR image, JARs, checksums, SBOMs and attestations, and creates
# the GitHub Release from docs/releases/0.1.0.md.

# Keep the release branch until it has carried release fixes and the next
# snapshot back into develop.
git switch release/0.1.0
mvn versions:set -DnewVersion=0.2.0-SNAPSHOT -DprocessAllModules -DgenerateBackupPoms=false
git commit -am "chore: open 0.2.0-SNAPSHOT"
git push origin release/0.1.0
gh pr create --base develop --head release/0.1.0 --title "Merge back 0.1.0"
gh pr merge --merge                # after every required check is green

git branch -d release/0.1.0
git push origin --delete release/0.1.0
```

Only bug fixes go onto a `release/*` branch. Anything else waits for `develop`.

## Shipping a hotfix

Same shape, but cut from `main` because `develop` may already contain unreleased
work that must not ship with the fix.

```bash
git switch main
git switch -c hotfix/0.1.1

# ... fix, with a test that fails without it ...
mvn versions:set -DnewVersion=0.1.1 -DprocessAllModules -DgenerateBackupPoms=false
mvn verify
git commit -am "fix: <what was broken>"
git push -u origin hotfix/0.1.1

gh pr create --base main --head hotfix/0.1.1 --title "Hotfix 0.1.1"
gh pr merge --merge

git fetch origin
git switch main
git merge --ff-only origin/main
git tag -a v0.1.1 -m "0.1.1"
git push origin v0.1.1

# If no release branch is open, open a PR from the hotfix branch into develop.
# Resolve only the version conflict while preserving develop's newer snapshot.
gh pr create --base develop --head hotfix/0.1.1 --title "Merge back 0.1.1"
gh pr merge --merge
git branch -d hotfix/0.1.1
git push origin --delete hotfix/0.1.1
```

If a `release/*` branch is open when the hotfix lands, merge the hotfix into
that branch rather than into `develop`; the release branch carries it to
`develop` when it closes. Merging into both duplicates the commit.

## Tooling

The `git flow` CLI is not required — every command above is plain git, apart
from the `gh` pull request into `main`, which the web UI does equally well. Its
`finish` commands merge into `main` locally, which the remote refuses; use it,
if at all, for starting branches. The repository is nonetheless configured for
it, so `git flow init` on a machine that has it will adopt these names rather
than prompt:

```
gitflow.branch.master   main
gitflow.branch.develop  develop
gitflow.prefix.feature  feature/
gitflow.prefix.release  release/
gitflow.prefix.hotfix   hotfix/
gitflow.prefix.versiontag v
```

## What CI enforces

Pushes to GitFlow branches and pull requests into `main` or `develop` run full
Maven verification, workflow lint, all buildable image builds and Trivy scans,
CodeQL and the deployed API/CLI E2E path. Pull requests additionally run
Dependency Review. Security analysis also runs weekly. A release branch is
therefore already proven before it reaches `main`.

An annotated `vMAJOR.MINOR.PATCH` tag on `main` starts delivery. Its Maven
version must be non-snapshot and matching, and
`docs/releases/MAJOR.MINOR.PATCH.md` must use the established release-note
sections. Only the base Linux AMD64 image is published; optional engine packs
remain operator-built.

## What the remote enforces

Repository rulesets on GitHub, not classic branch protection, enforce:

- **main and develop are append-only** — neither can be deleted or
  force-pushed.
- **main and develop change only through a pull request** — merged with a merge
  commit, the only method allowed; no approving review is required.
- **required quality gates** — Workflow lint, Maven verify, Container build and
  scan, CodeQL, Dependency Review and API/CLI E2E must all pass.

The release or hotfix branch stays alive until its merge-back PR is complete.
This preserves GitFlow without granting a direct-push exception to `develop`.
