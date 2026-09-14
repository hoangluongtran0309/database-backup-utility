<!--
  Target `develop` for a feature, `main` for a release or hotfix.
  See docs/branching.md.
-->

## What this changes

<!-- One or two sentences. Which ROADMAP slice, if it is one. -->

## Definition of done

- [ ] Works end to end from the browser — no manual SQL needed to use it
- [ ] `mvn verify` green from a clean checkout, and green because the tests
      **ran**: nothing skips itself when a binary or container is missing
- [ ] Unit tests are `*Test.java`, container tests are `*IT.java`, no H2
- [ ] Schema changes are one new `V<n>__*.sql`; no applied migration was edited
- [ ] Nothing built ahead of need: no unimplemented interface, no enum value
      without code that handles it, no column nobody reads
- [ ] `ROADMAP.md` ticked only for what is actually finished
- [ ] `README.md` updated if the way to run it or its configuration changed
- [ ] `docs/` describes the code as it now is
- [ ] An ADR added only if this made a decision with a real trade-off
