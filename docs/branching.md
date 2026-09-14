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

git switch develop
git merge --no-ff feature/03-run-logical-backup
git branch -d feature/03-run-logical-backup
```

## Cutting a release

The version in the parent `pom.xml` is hardcoded — there is no `${revision}`
and no flatten plugin — so the release branch is where it changes, twice.

```bash
git switch develop
git switch -c release/0.1.0

# Drop -SNAPSHOT. One edit, in the parent pom only; the modules inherit it.
mvn versions:set -DnewVersion=0.1.0 -DprocessAllModules -DgenerateBackupPoms=false
mvn verify
git commit -am "chore: release 0.1.0"

git switch main
git merge --no-ff release/0.1.0
git tag -a v0.1.0 -m "0.1.0"

# Back into develop, or develop would keep the old version and lose the tag's
# ancestry. This second merge is the step people forget.
git switch develop
git merge --no-ff release/0.1.0
mvn versions:set -DnewVersion=0.2.0-SNAPSHOT -DprocessAllModules -DgenerateBackupPoms=false
git commit -am "chore: open 0.2.0-SNAPSHOT"

git branch -d release/0.1.0
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

git switch main
git merge --no-ff hotfix/0.1.1
git tag -a v0.1.1 -m "0.1.1"

git switch develop
git merge --no-ff hotfix/0.1.1      # or into the live release/* branch, if one exists
git branch -d hotfix/0.1.1
```

If a `release/*` branch is open when the hotfix lands, merge the hotfix into
that branch rather than into `develop`; the release branch carries it to
`develop` when it closes. Merging into both duplicates the commit.

## Tooling

The `git flow` CLI is not required — every command above is plain git. The
repository is nonetheless configured for it, so `git flow init` on a machine
that has it will adopt these names rather than prompt:

```
gitflow.branch.master   main
gitflow.branch.develop  develop
gitflow.prefix.feature  feature/
gitflow.prefix.release  release/
gitflow.prefix.hotfix   hotfix/
gitflow.prefix.versiontag v
```

## What CI enforces

`mvn verify` runs on pushes to `main`, `develop`, `release/*` and `hotfix/*`,
and on every pull request targeting `main` or `develop`. A release branch is
therefore already proven before it reaches `main`.

Branch protection on the remote is **not yet configured**, because nothing has
been pushed. Once `main` and `develop` exist on the remote, the rules worth
having are: require a pull request, require the `mvn verify` check to pass,
forbid force-pushes, and forbid deletion.
