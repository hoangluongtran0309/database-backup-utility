# Architecture overview

A hexagonal application in four Maven modules. The module boundary is the
architectural boundary: crossing it wrongly is a compile error, not a review
comment.

## Modules and dependency direction

```
core        no dependencies at all — plain Java and Lombok
  ↑     ↑
  |     └── adapters      JPA, encryption; everything technical
  |              ↑
  └── application        use cases; depends on core ports only
           ↑     ↑
           └─ web ┘      composition root: Spring MVC + Thymeleaf
```

| Module | Holds | May depend on |
| --- | --- | --- |
| `core` | Domain model, ports, domain exceptions | nothing |
| `adapters` | Outbound adapters — persistence, encryption, Flyway migrations | `core` |
| `application` | Use case orchestration | `core` |
| `web` | HTTP controllers, forms, templates, `main()` | `application`, `adapters` |

Two consequences are worth stating plainly, because they are the reason for the
split rather than side effects of it:

- **`core` has an empty dependency list.** Not "we avoid importing Spring in
  core" — the jars are not on its classpath, so the import does not compile.
- **`application` does not depend on `adapters`.** Use cases talk to interfaces
  in `core` and are therefore unit-tested with mocked ports, no Spring context
  and no Docker. `web` is the single place that sees both sides, and Spring
  wires them there at startup.

## Where the rules live

- **Business rules are on the domain model.** `DatabaseTarget` validates itself
  in its constructor, so holding a reference to one is already proof that its
  values are sane. Services do not check first.
- **Use case logic is in `application`, never in `web`.** Controllers translate
  HTTP into a use case call and a view name.
- **Only `application` calls `EncryptionPort`.** Adapters never hold the key, so
  there is exactly one place in the system where a secret is unwrapped, and one
  place to review. `DatabaseTarget.passwordCiphertext` holds ciphertext at every
  moment of its life; a plaintext password belongs in a separate type named for
  what it carries.

## PostgreSQL is the metadata store, not a backup engine

This tool stores its own metadata — registered targets, and later the execution
history — in PostgreSQL. That is the only reason `org.postgresql` appears in the
build and the only reason the Flyway migrations are written in PostgreSQL's
dialect.

**PostgreSQL is not a database this tool can back up.** There is no PostgreSQL
engine adapter, no `POSTGRESQL` enum value, and no plan for one in the current
roadmap. MySQL is the only engine, which is also why `database_targets` has no
`engine` column: the migration that introduces a second engine is the one that
should add it.

## Testing

`*Test.java` is a plain JUnit test run by Surefire in `mvn test`. `*IT.java` is
a Testcontainers test run by Failsafe in `mvn verify`. H2 is not used anywhere —
the repository tests depend on a functional unique index, PostgreSQL's own
constraint-violation message, and Hibernate schema validation against the real
Flyway output, and H2 would misreport all three.

No test may skip itself because something it needs is absent. A test that turns
green by not running is worse than no test at all.

## Database migrations

Flyway migrations live in `adapters/src/main/resources/db/migration/`, named
`V<n>__snake_case_description.sql`. Once a migration has been applied its SQL is
never edited; a correction is a new migration. Hibernate runs with
`ddl-auto: validate`, so a mapping that drifts from the schema fails at startup
rather than in production.
