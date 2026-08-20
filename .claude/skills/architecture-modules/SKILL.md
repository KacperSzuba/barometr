---
name: architecture-modules
description: Module boundaries and dependency rules for the barometr backend — where a new class, package or Gradle module belongs, what may depend on what, and how contexts talk to each other. Use when adding a module or context, moving code between modules, deciding whether something is platform or domain, wiring a bean into :app, exposing something across a boundary, publishing or consuming an application event, or when a Modulith/ArchUnit boundary test fails.
---

# Module boundaries

**Apply when** creating or moving a module, deciding where a class lives, or making
two contexts talk to each other.

## The layout

A Gradle module is a **service candidate**: everything one bounded context needs to
run travels with it, so extracting it later is a move, not an untangling.

```
:app             assembly only — wiring, security chain, exception mapping. No domain logic.
:shared          value types. No Spring, no persistence, no HTTP.
:shared-testing  test harness: migrated Postgres, movable clock. Test classpath only.
:platform        technical capability with no domain meaning: http · jobs · storage.
:identity        accounts, tokens, sessions.
:sources         the registry of what is ingested, and each connector's position.
:ingestion       the raw archive, and the connectors that fill it.
:corpus          documents, versions, extracted text, chunks.
:legislative     acts, drafts, the legislative process.
```

Inside a context: `pl.barometr.<context>.api` is the published contract,
`pl.barometr.<context>.internal` is everything else, including all persistence.
Directories live under `modules/`; the project paths are short because a module is a
top-level thing.

## Rules

1. **A context's `internal` package is invisible to everyone else.** Reaching past
   `api` couples the caller to storage and service shapes that must stay free to
   change. Since each context is one Gradle module, the compiler will not stop you —
   [ModularityTest.kt](app/src/test/kotlin/pl/barometr/ModularityTest.kt) is what
   does, and a context missing from its `CONTEXTS` list is a context nobody checks.
2. **Every `api` package is declared a named interface**, in a `package-info.java`
   beside it:
   ```java
   @org.springframework.modulith.NamedInterface("api")
   package pl.barometr.<context>.api;
   ```
   Modulith treats a sub-package as internal unless told otherwise, so without this
   every legitimate use of the contract is reported as a violation and every
   illegitimate use of `internal` is not. Java, because Kotlin has no package
   annotations.
3. **`api` holds contracts and value types only** — interfaces, events, ids, DTOs.
   No Spring beans, no jOOQ, no HTTP. If a contract needs a framework to express
   itself, it is not a contract yet.
4. **`shared` is value types with no framework at all.** The moment shared code
   wants Spring, persistence or HTTP, it belongs to a context. `ContentHash`,
   `DomainException`, `Ids` are the whole of what qualifies.
5. **`platform` may not depend on any context**, because a technical capability that
   knows a domain is a context in disguise. Contexts depend on platform; never back.
6. **`:app` is the only module allowed to see every implementation**, and holds no
   domain logic — assembling the system is its entire purpose. Security chain,
   `@RestControllerAdvice` mapping and `main` live there and nowhere else.
7. **Anything crossing a boundary is a value type.** Never a persistence row, never a
   jOOQ record, never an entity. `UserLookupAdapter` returns `UserSnapshot` for
   exactly this reason — see
   [UserLookup.kt](modules/identity/src/main/kotlin/pl/barometr/identity/api/UserLookup.kt).
8. **Prefer an event to a call when the caller does not need an answer.**
   `@ApplicationModuleListener` makes delivery asynchronous and transactional —
   Spring Modulith writes the publication to `event_publication` and retries it, so
   it is the outbox. `RawDocumentIngested` is the model: ingestion knows nothing
   about extraction, indexing or alerting, and they all hang off it.
9. **Prefer a published port when the caller does need an answer.** A read port
   (`UserLookup`, `SourceRegistry`) is an interface in `api`, implemented in
   `internal`. The implementing class is never referenced by name outside its context.
10. **No cross-schema foreign keys** — a FK across contexts is the same coupling in
   SQL, and it welds two schemas into one migration order. Integrity across a
   boundary is the pipeline's job; see `database-schema`.
11. **One context, one database schema, one changelog.** A context that reads another
    context's tables has skipped its `api`.

## Patterns to copy

- Contract, event and port for a context:
  [identity-api](modules/identity/src/main/kotlin/pl/barometr/identity/api/)
- Boundary tests to extend when adding a context:
  [ModularityTest.kt](app/src/test/kotlin/pl/barometr/ModularityTest.kt)
- Assembly and nothing else:
  [BarometrApplication.kt](app/src/main/kotlin/pl/barometr/BarometrApplication.kt)

## Never

- **Never add a Gradle module to express a layer or a split contract.** Twenty
  projects became nine when `-api`/`-impl` pairs and the four `platform/*` projects
  were merged; five of the twenty held no code at all (review D20). `corpus` and
  `legislative` currently hold only a schema, which is content — an empty placeholder
  is not.
- **Never put a controller, repository or `@Configuration` in `api`.** It stops being
  a contract the moment a consumer inherits a runtime from it.
- **Never let `:app` contain domain logic**, however small — it is the one place with
  no boundary to stop it growing.
- **Never reach into another context to avoid publishing a contract.** If the
  contract is missing, add it; the shortcut is permanent.
- **Never split a context into more Gradle modules to express layers.** Layers are
  packages. Modules are deployment units.
- **Never let a connector become its own module.** It is only useful with the SPI it
  implements and the sink it writes to; splitting them means a context that cannot be
  built, tested or extracted without three other projects.

## Verify

```bash
./gradlew :app:test --tests 'pl.barometr.ModularityTest'
```

Then check that the new context appears in the printed module structure with the
dependencies you expect, and that nothing outside it names its `internal` package.
