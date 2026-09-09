# ADR-001: Four Maven modules, with `application` independent of `adapters`

**Status**: Accepted
**Date**: 2026-09

## Context

This project is a rewrite. Its predecessor grew to 376 Java files — 112 in
`core` alone — largely because structure was added ahead of need. The rewrite
starts from one narrow capability, so the first question is how much structure
is justified on day one, when the whole application is roughly a dozen
production classes.

Two options were real:

1. **One module**, with `core` / `application` / `adapters` / `web` as packages
   and an ArchUnit test failing the build when a package imports something it
   should not.
2. **Four modules**, one per layer, so the boundary is enforced by the compiler.

Option 1 is less ceremony: one pom instead of five, a faster reactor, and
moving a class across a boundary is a rename rather than a pom edit plus an IDE
reimport.

## Decision

Four modules: `core`, `adapters`, `application`, `web`.

Crucially, **`application` depends on `core` only, not on `adapters`.** `web` is
the composition root and the single module that sees both.

No ArchUnit. With this layout its rules would restate what the poms already
guarantee.

## Rationale

The decisive property is not tidiness, it is that `core` has an *empty*
dependency list. A domain class cannot import Spring or JPA because those jars
are not on its classpath — there is no rule to remember and no test to keep
passing. An ArchUnit rule in a single module would enforce the same thing, but
only for imports someone thought to write a rule about, and only at test time.

The same argument decides the direction inside the hexagon. If `application`
depended on `adapters`, nothing would stop a use case reaching past its ports
into a JPA repository, and the moment one did, every use case test would need a
Spring context and a container. Keeping that dependency absent is what makes
`ManageDatabaseTargetServiceTest` a plain JUnit test with mocked ports.

## Consequences

- A feature touches several modules. That is the accepted price.
- Five poms exist for what is currently a small amount of code.
- `web` is the only module allowed to know which adapter satisfies which port.
- Flyway migrations live in `adapters`, because that is the module that owns
  persistence.
- If the module split later proves to cost more than it returns, collapsing to
  one module means reintroducing ArchUnit to replace the compiler.
